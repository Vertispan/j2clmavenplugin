package net.cardosi.mojo;

import com.google.common.io.CharStreams;
import java.util.logging.Level;
import net.cardosi.mojo.cache.CachedProject;
import net.cardosi.mojo.cache.DiskCache;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugin.descriptor.PluginDescriptor;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.DefaultProjectBuildingRequest;
import org.apache.maven.project.MavenProject;
import org.apache.maven.project.ProjectBuildingException;
import org.apache.maven.project.ProjectBuildingRequest;
import org.apache.maven.shared.utils.io.DirectoryScanner;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.logging.LogType;
import org.openqa.selenium.logging.LoggingPreferences;
import org.openqa.selenium.support.ui.FluentWait;

import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.stream.Collectors;

@Mojo(name = "test", requiresDependencyResolution = ResolutionScope.TEST)
public class TestMojo extends AbstractBuildMojo implements ClosureBuildConfiguration {
    /**
     * The dependency scope to use for the classpath.
     * <p>The scope should be one of the scopes defined by org.apache.maven.artifact.Artifact. This includes the following:
     * <ul>
     * <li><i>compile</i> - system, provided, compile
     * <li><i>runtime</i> - compile, runtime
     * <li><i>compile+runtime</i> - system, provided, compile, runtime
     * <li><i>runtime+system</i> - system, compile, runtime
     * <li><i>test</i> - system, provided, compile, runtime, test
     * </ul>
     */
    @Parameter(defaultValue = Artifact.SCOPE_TEST, required = true)
    protected String classpathScope;

    @Parameter(defaultValue = "${project.artifactId}/test.js", required = true)
    protected String initialScriptFilename;

    @Parameter(defaultValue = "${project.build.directory}/${project.build.finalName}-test", required = true)
    protected String webappDirectory;

    @Parameter
    protected Set<String> externs = new TreeSet<>();

    @Parameter
    protected List<String> tests;

    @Parameter(defaultValue = "BUNDLE")
    protected String compilationLevel;

    @Parameter
    protected Map<String, String> defines = new TreeMap<>();

    @Parameter(defaultValue = "false", property = "maven.test.skip")
    protected boolean skipTests;

    @Parameter(defaultValue = "**/Test*.java,**/*Test.java, **/GwtTest*.java")//TODO **/Test*.js
    private List<String> includes;

    //TODO **/*_AdapterSuite.js
    @Parameter
    private List<String> excludes;


    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        if (skipTests) {
            return;
        }

        Map<String, String> failedTests = new HashMap<>();

        PluginDescriptor pluginDescriptor = (PluginDescriptor) getPluginContext().get("pluginDescriptor");
        String pluginVersion = pluginDescriptor.getVersion();

        //TODO need to be very careful about allowing these to be configurable, possibly should tie them to the "plugin version" aspect of the hash
        //     or stitch them into the module's dependencies, that probably makes more sense...
        List<File> extraClasspath = Arrays.asList(
                getFileWithMavenCoords(jreJar),
                getFileWithMavenCoords(internalAnnotationsJar),
                getFileWithMavenCoords(jsinteropAnnotationsJar),
                getFileWithMavenCoords("javax.annotation:jsr250-api:1.0"),
                getFileWithMavenCoords("com.vertispan.jsinterop:base:1.0.0-SNAPSHOT"),//TODO stop hardcoding this when goog releases a "base" which actually works on both platforms
                getFileWithMavenCoords("com.vertispan.j2cl:junit-processor:0.4-SNAPSHOT"),
                getFileWithMavenCoords(junitAnnotations)
        );

        List<File> extraJsZips = Arrays.asList(
                getFileWithMavenCoords(jreJsZip),
                getFileWithMavenCoords(bootstrapJsZip),
                getFileWithMavenCoords(testJsZip)
        );

        DiskCache diskCache = new DiskCache(pluginVersion, gwt3BuildCacheDir, getFileWithMavenCoords(javacBootstrapClasspathJar), extraClasspath, extraJsZips);
        diskCache.takeLock();
        ProjectBuildingRequest request = new DefaultProjectBuildingRequest(mavenSession.getProjectBuildingRequest());

        // for each project in the reactor, check if it is an app we should compile
        // TODO how do we want to pick which one(s) are actual apps?
        LinkedHashMap<String, CachedProject> projects = new LinkedHashMap<>();

        // if key defines aren't set, assume "test defaults" - need to doc the heck out of this
//        defines.putIfAbsent("jre.checkedMode", "ENABLED");
//        defines.putIfAbsent("jre.checks.checkLevel", "NORMAL");
//        defines.putIfAbsent("jsinterop.checks", "ENABLED");

        //scan for things that look like tests, hope that they were correctly annotated?
        //TODO when we have more of a "task" layout to build with, look for js tests instead of java ones, since we know they'll work


        try {
            CachedProject source = loadDependenciesIntoCache(project.getArtifact(), project, false, projectBuilder, request, diskCache, pluginVersion, projects, Artifact.SCOPE_TEST, getDependencyReplacements(), "* ");

            // given that set of tasks, we'll chain one more on the end, and watch _that_ for changes
            List<CachedProject> children = new ArrayList<>(source.getChildren());
            children.add(source);
            CachedProject e = new CachedProject(diskCache, project.getArtifact(), project, children, project.getTestCompileSourceRoots());

            diskCache.release();

            for (ClosureBuildConfiguration config : getTestConfigs(this, tests, project, includes, excludes)) {
                e.registerAsApp(config).join();
                getLog().info("Test started: " + ((TestConfig)config).getTest());
                // write a simple html file to that output dir
                //TODO parallelize this - run once each is done, possibly concurrently
                //TODO don't fail on the first test that doesn't work
                String startupHtmlFile;
                try {
                    File outputJs = new File(new File(webappDirectory), config.getInitialScriptFilename());
                    Path junitStartupFile = Paths.get(outputJs.getParent(), outputJs.getName().substring(0, outputJs.getName().length() - 2) + "html");
                    Files.createDirectories(junitStartupFile.getParent());
                    String fileContents = CharStreams.toString(new InputStreamReader(TestMojo.class.getResourceAsStream("/junit.html")));
                    fileContents = fileContents.replace("<TEST_SCRIPT>", outputJs.getName());

                    Files.write(junitStartupFile, fileContents.getBytes("UTF-8"), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
                    startupHtmlFile = junitStartupFile.toAbsolutePath().toString();
                } catch (IOException ex) {
                    throw new UncheckedIOException(ex);
                }
                // assuming that was successful, start htmlunit to run the test
                WebDriver driver = null;
                try {
                    ChromeOptions chromeOptions = new ChromeOptions();
                    chromeOptions.setHeadless(true);
                    LoggingPreferences loggingPreferences = new LoggingPreferences();
                    loggingPreferences.enable(LogType.BROWSER, Level.ALL);
                    chromeOptions.setCapability("goog:loggingPrefs",
                        loggingPreferences);
                    driver = new ChromeDriver(chromeOptions);
                    driver.get("file://" + startupHtmlFile);
                    // loop and poll if tests are done
                    new FluentWait<>(driver)
                            .withTimeout(Duration.ofMinutes(1))
                            .withMessage("Tests failed to finish in timeout")
                            .pollingEvery(Duration.ofMillis(100))
                            .until(d -> isFinished(d));
                    // check for success
                    if (!isSuccess(driver)) {
                        // print the content of the browser console to the log
                        this.analyzeLog(driver);
                        failedTests.put(((TestConfig) config).getTest(), startupHtmlFile);
                        getLog().error("Test failed!");
                    } else {
                        getLog().info("Test passed!");
                    }
                } catch (Exception ex) {
                    failedTests.put(((TestConfig) config).getTest(), startupHtmlFile);
                    if (!Objects.isNull(driver)) {
                        this.analyzeLog(driver);
                    }
                    getLog().error("Test failed!");
                    getLog().error(pimpOutput(String.format(ex.getMessage())));
                } finally {
                    if (driver != null) {
                        driver.quit();
                    }
                }
            }
        } catch (ProjectBuildingException | IOException e) {
            throw new MojoExecutionException("Failed to build project structure", e);
        }

        if(failedTests.isEmpty()) {
            getLog().info("All tests were passed successfully!");
        } else {
            failedTests.forEach((name, startupHtmlFile) -> getLog().error(String.format("Test %s failed, please try manually %s", name, startupHtmlFile)));
            throw new MojoFailureException("At least one test failed");
        }
    }

    private void analyzeLog(WebDriver driver) {
        logMessageFromDriver(LogType.BROWSER,
            driver);
        // in case we got a closure message on the html page, grab it & print it to the log
        if (driver.getPageSource().contains("closureTestRunnerLog")) {
            this.logMessageFromDriverHTML(driver.getPageSource());
        }
    }

    private void logMessageFromDriver(String logType, WebDriver driver) {
        if (driver != null) {
            driver.manage().logs().get(logType).getAll().forEach(l -> {
                    if (Level.SEVERE.equals(l.getLevel())) {
                        getLog().error(String.format(pimpOutput(l.getMessage())));
                    } else {
                        getLog().info(String.format(pimpOutput(l.getMessage())));
                    }
                });
        }
    }

    private void logMessageFromDriverHTML(String pageSource) {
        Document document = Jsoup.parseBodyFragment(pageSource);
        Element body = document.body();
        Element closureTestRunningLog = document.getElementById("closureTestRunnerLog");
        if (!Objects.isNull(closureTestRunningLog)) {
            closureTestRunningLog.getElementsByTag("div")
                .forEach(t -> getLog().error(pimpOutput(t.text())));
        }
    }

    /**
     * While grabbing the content from the browser page or content of the console,
     * the grabbed content is:
     * <ul>
     *   <li>put into '"'</li>
     *   <li>all line feeds are marked with '\n' which will not work on the console</li>
     *   <li>all &lt; are replaced with 'u003C'.</li>
     * </ul>
     * F.e.: a stacktrace is one log line. Without improving the log, the stacktrace will
     * be logged in one line which made it hard to read.   *
     * <p/>
     * This method
     * <lu>
     *   <li>removes the '"'</li>
     *   <li>replaces the '\n' for line feeds with '%n' which will work on the console</li>
     *   <li>replaces the 'u003C' for line feeds with '&lt;' which will work on the console</li>
     * </lu>
     *
     * @param input log messagae to handle
     * @return update log message
     */
    private String pimpOutput(String input) {
        String text;
        // use the first '"' as start of content
        if (input.contains("\"")) {
            text = input.substring(input.indexOf("\""));
        } else {
            text = input;
        }
        // replace all '"'
        while (text.contains("\"")) {
            text = text.replace("\"",
                "");
        }
        // replace '/n' with '%n'
        while (text.contains("\\n")) {
            text = text.replace("\\n",
                "%n");
        }
        // replace 'u003C' with '<'
        while (text.contains("\\u003C")) {
            text = text.replace("\\u003C",
                "<");
        }
        return text;
    }

    private static boolean isSuccess(WebDriver d) {
        return (Boolean) ((JavascriptExecutor) d).executeScript("return window.G_testRunner.isSuccess()");
    }

    private static boolean isFinished(WebDriver d) {
        return (Boolean) ((JavascriptExecutor) d).executeScript("return !!(window.G_testRunner && window.G_testRunner.isFinished())");
    }

    /**
     * If specific tests are specified, will use them, otherwise will look for the tests through includes/excludes,
     * and produce a build config each.
     */
    public static List<ClosureBuildConfiguration> getTestConfigs(ClosureBuildConfiguration baseConfig, List<String> tests, MavenProject project, List<String> includes, List<String> excludes) {
        List<String> testEntrypoints;
        if (tests == null || tests.isEmpty()) {
            testEntrypoints = project.getTestCompileSourceRoots().stream()
                    .flatMap(s -> getTestEntrypoints(new File(s), includes, excludes).stream())
                    .distinct()
                    .map(f -> f.replaceAll("\\.java$", "").replaceAll("/", "."))
                    .collect(Collectors.toList());
        } else {
            testEntrypoints = tests;
        }
        return testEntrypoints.stream().map(name -> new TestConfig(name, baseConfig)).collect(Collectors.toList());
    }

    private static List<String> getTestEntrypoints(File testSourceDir, List<String> includes, List<String> excludes) {
        DirectoryScanner scanner = new DirectoryScanner();
        scanner.setBasedir(testSourceDir);
        scanner.setIncludes(includes.toArray(new String[0]));
        if (excludes != null) {
            scanner.setExcludes(excludes.toArray(new String[0]));
        }
        scanner.scan();

        return Arrays.asList(scanner.getIncludedFiles());
    }

    @Override
    public String getClasspathScope() {
        return classpathScope;
    }

    @Override
    public List<String> getEntrypoint() {
        throw new UnsupportedOperationException("This method should not be called directly: TestMojo.getEntrypoint");
    }

    @Override
    public Set<String> getExterns() {
        return externs;
    }

    @Override
    public Map<String, String> getDefines() {
        return defines;
    }

    @Override
    public String getWebappDirectory() {
        return webappDirectory;
    }

    @Override
    public String getInitialScriptFilename() {
        return initialScriptFilename;
    }

    @Override
    public String getCompilationLevel() {
        return compilationLevel;
    }

    private static class TestConfig implements ClosureBuildConfiguration {

        private final String test;
        private final ClosureBuildConfiguration wrapped;

        private TestConfig(String test, ClosureBuildConfiguration wrapped) {
            this.test = test;
            this.wrapped = wrapped;
        }

        @Override
        public String getClasspathScope() {
            return wrapped.getClasspathScope();
        }

        @Override
        public List<String> getEntrypoint() {
            return Collections.singletonList("javatests." + test + "_AdapterSuite");
        }

        @Override
        public Set<String> getExterns() {
            return wrapped.getExterns();
        }

        @Override
        public Map<String, String> getDefines() {
            return wrapped.getDefines();
        }

        @Override
        public String getWebappDirectory() {
            return wrapped.getWebappDirectory();
        }

        @Override
        public String getInitialScriptFilename() {
            return wrapped.getInitialScriptFilename().substring(0, ".js".length()) + "-" + test + ".js";
        }

        @Override
        public String getCompilationLevel() {
            return wrapped.getCompilationLevel();
        }

        public String getTest() {
            return test;
        }
    }
}
