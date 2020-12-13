package net.cardosi.mojo;

import com.gargoylesoftware.htmlunit.BrowserVersion;
import com.google.common.io.CharStreams;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.logging.Level;

import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.google.javascript.jscomp.DependencyOptions;
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
import org.apache.maven.project.ProjectBuildingException;
import org.apache.maven.project.ProjectBuildingRequest;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.handler.ResourceHandler;
import org.eclipse.jetty.util.component.AbstractLifeCycle;
import org.eclipse.jetty.util.component.LifeCycle;
import org.eclipse.jetty.util.resource.Resource;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.htmlunit.HtmlUnitDriver;
import org.openqa.selenium.logging.LogType;
import org.openqa.selenium.logging.LoggingPreferences;
import org.openqa.selenium.support.ui.FluentWait;

import java.nio.file.*;
import java.time.Duration;
import java.util.*;

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

    /**
     * Describes how the output should be built - presently supports five modes, four of which are closure-compiler
     * "compilationLevel" argument options, and an additional special case for J2cl-base applcations. The quoted
     * descriptions here explain how closure-compiler defines them.
     * <ul>
     *     <li>
     *         {@code ADVANCED_OPTIMIZATIONS} - "ADVANCED_OPTIMIZATIONS aggressively reduces code size by renaming
     *         function names and variables, removing code which is never called, etc." This is typically what is
     *         expected for production builds.
     *     </li>
     *     <li>
     *         {@code SIMPLE_OPTIMIZATIONS} - "SIMPLE_OPTIMIZATIONS performs transformations to the input JS that
     *         do not require any changes to JS that depend on the input JS." Generally not useful in this plugin -
     *         slower than BUNDLE, much bigger than ADVANCED_OPTIMIZATIONS
     *     </li>
     *     <li>
     *         {@code WHITESPACE_ONLY} - "WHITESPACE_ONLY removes comments and extra whitespace in the input JS."
     *         Generally not useful in this plugin - slower than BUNDLE, much bigger than ADVANCED_OPTIMIZATIONS
     *     </li>
     *     <li>
     *         {@code BUNDLE} - "Simply orders and concatenates files to the output." The GWT fork of closure also
     *         prepends define statements, and provides wiring for sourcemaps.
     *     </li>
     *     <li>
     *         {@code BUNDLE_JAR} - Not a "real" closure-compiler option. but instead invokes BUNDLE on each
     *         classpath entry and generates a single JS file which will load those bundled files in order. Enables
     *         the compiler to cache results for each dependency, rather than re-generate a single large JS file.
     *     </li>
     * </ul>
     */
    @Parameter(defaultValue = CachedProject.BUNDLE_JAR, property = "compilationLevel")
    protected String compilationLevel;

    /**
     * ECMAScript language level of generated JavasScript. Values correspond to the Closure Compiler reference:
     * https://github.com/google/closure-compiler/wiki/Flags-and-Options
     */
    @Parameter(defaultValue = "ECMASCRIPT5", property = "languageOut")
    protected String languageOut;

    @Parameter
    protected Map<String, String> defines = new TreeMap<>();

    /**
     * Whether or not to leave Java assert checks in the compiled code. In j2cl:test, defaults to true. Has no
     * effect when the compilation level isn't set to ADVANCED_OPTIMIZATIONS, assertions will always remain
     * enabled.
     */
    @Parameter(defaultValue = "true")
    protected boolean checkAssertions;

    /**
     * Closure flag: "Rewrite ES6 library calls to use polyfills provided by the compiler's runtime."
     * Unlike in closure-compiler, defaults to false.
     */
    @Parameter(defaultValue = "false")
    protected boolean rewritePolyfills;

    @Parameter(defaultValue = "false", property = "maven.test.skip")
    protected boolean skipTests;

    @Parameter(defaultValue = "**/Test*.java,**/*Test.java, **/GwtTest*.java")//TODO **/Test*.js
    private List<String> includes;

    //TODO **/*_AdapterSuite.js
    @Parameter
    private List<String> excludes;

    //TODO make this more flexible
    @Parameter(defaultValue = "htmlunit")
    protected String webdriver;

    // exists only as long as entrypoint does, expect this to be removed soon
    @Deprecated
    @Parameter(defaultValue = "SORT_ONLY")
    protected String dependencyMode;

    @Parameter(defaultValue = "false")
    protected boolean enableSourcemaps;

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
                getFileWithMavenCoords("com.vertispan.jsinterop:base:" + Versions.VERTISPAN_JSINTEROP_BASE_VERSION),//TODO stop hardcoding this when goog releases a "base" which actually works on both platforms
                getFileWithMavenCoords("com.vertispan.j2cl:junit-processor:" + Versions.J2CL_VERSION),
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
            // Build the dependency tree for the project itself. Note that this picks up the scope of the test side of things, but uses the app sources, which isn't exactly right.
            CachedProject source = loadDependenciesIntoCache(project.getArtifact(), project, false, projectBuilder, request, diskCache, pluginVersion, projects, Artifact.SCOPE_TEST, getDependencyReplacements(), "* ");

            // Given that set of tasks, we'll chain one more on the end - this is the one that will have the actual test sources+resources. To be fully correct,
            // only this should have the scope=test deps on it
            List<CachedProject> children = new ArrayList<>(source.getChildren());
            children.add(source);
            CachedProject e = new CachedProject(diskCache, project.getArtifact(), project, children, project.getTestCompileSourceRoots(), project.getTestResources());

            diskCache.release();

            // Run the test's annotation processor so that we can find which tests were prepared, and treat each one as an entrypoint
            File generatedSources = e.generatedSources().join().getAnnotationSourcesDir();
            File testSummaryJson = new File(generatedSources, "test_summary.json");
            if (!testSummaryJson.exists() || !testSummaryJson.isFile()) {
                getLog().warn("No generated test_summary.json in generated output directory, either no tests, not annotated correctly or no junit-processor was on the classpath? " + testSummaryJson);
                return;
            }

            // Grab the JSON that describes all tests, and use it to see which ones we'll actually run (user-specified tests, excludes, includes)
            final String[] generatedTests;
            try (final Reader reader = new BufferedReader(new FileReader(testSummaryJson))) {
                generatedTests = new GsonBuilder().create().
                        <Map<String, String[]>>fromJson(reader, new TypeToken<Map<String, String[]>>() {}.getType())
                        .get("tests");
            }

            //TODO something like getTestConfigs to manage includes/exclude, manually specified tests

            for (String generatedTest : generatedTests) {
                // this is pretty hacky, TODO clean this up, put the intermediate js file somewhere nicer
                String testFilePathWithoutSuffix = generatedTest.substring(0, generatedTest.length() - 3);
                File testJs = new File(generatedSources, testFilePathWithoutSuffix + ".testsuite");
                Path tmp = Files.createTempDirectory(testJs.getName() + "-dir");
                Path copy = tmp.resolve(testFilePathWithoutSuffix + ".js");
                if (!Files.exists(copy.getParent())) {
                    Files.createDirectories(copy.getParent());
                }
                Files.copy(testJs.toPath(), copy);
                String testClass = testFilePathWithoutSuffix.replaceAll("/", ".");

                // Synthesize a new project which only depends on the last one, and only contains the named test's .testsuite content, remade into a one-off JS file
                ArrayList<CachedProject> finalChildren = new ArrayList<>(e.getChildren());
                finalChildren.add(e);
                CachedProject t = new CachedProject(diskCache, project.getArtifact(), project, finalChildren, Collections.singletonList(tmp.toString()), Collections.emptyList());
                TestConfig config = new TestConfig(testClass, this);

                // build this project normally
                if (getCompilationLevel().equalsIgnoreCase(CachedProject.BUNDLE_JAR)) {
                    t.registerAsChunkedApp(config).join();
                } else {
                    t.registerAsApp(config).join();
                }

                getLog().info("Test started: " + config.getTest());
                // write a simple html file to that output dir
                //TODO parallelize this - run once each is done, possibly concurrently
                //TODO don't fail on the first test that doesn't work
                Path startupHtmlFile;
                try {
                    Path webappDirPath = Paths.get(webappDirectory);
                    Path outputJsPath = webappDirPath.resolve(config.getInitialScriptFilename());
                    File outputJs = new File(new File(webappDirectory), config.getInitialScriptFilename());
                    Path junitStartupPath = outputJsPath.resolveSibling(outputJsPath.getFileName().toString().substring(0, outputJs.getName().length() - 2) + "html");
                    Files.createDirectories(junitStartupPath.getParent());
                    String fileContents = CharStreams.toString(new InputStreamReader(TestMojo.class.getResourceAsStream("/junit.html")));
                    fileContents = fileContents.replace("<TEST_SCRIPT>", outputJs.getName());

                    Files.write(junitStartupPath, fileContents.getBytes(StandardCharsets.UTF_8), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
                    startupHtmlFile = webappDirPath.relativize(junitStartupPath);
                } catch (IOException ex) {
                    throw new UncheckedIOException(ex);
                }

                // Start a webserver TODO start just once for all tests
                Server server = new Server(0);

                // Tell jetty how to serve our compiled content
                ResourceHandler resourceHandler = new ResourceHandler();
                resourceHandler.setDirectoriesListed(true);//enabled for easier debugging, if we introduce a "manual" mode
                resourceHandler.setBaseResource(Resource.newResource(webappDirectory));

                server.setHandler(resourceHandler);
                server.start();

                // With the server started, start a browser too so they work in parallel
                WebDriver driver = createBrowser();

                // Wait until server is ready
                if (!server.isStarted()) {
                    CountDownLatch started = new CountDownLatch(1);
                    server.addLifeCycleListener(new AbstractLifeCycle.AbstractLifeCycleListener() {
                        @Override
                        public void lifeCycleStarted(LifeCycle event) {
                            started.countDown();
                        }
                    });
                    started.await();
                }
                int port = ((ServerConnector) server.getConnectors()[0]).getLocalPort();

                try {
                    String url = "http://localhost:" + port + "/" + startupHtmlFile.toString();
                    System.out.println("fetching " + url);
                    driver.get(url);

                    // Loop and poll if tests are done
                    new FluentWait<>(driver)
                            .withTimeout(Duration.ofMinutes(1))
                            .withMessage("Tests failed to finish before timeout")
                            .pollingEvery(Duration.ofMillis(100))
                            .until(d -> isFinished(d));
                    // Check for success
                    if (!isSuccess(driver)) {
                        // Print the content of the browser console to the log
                        this.analyzeLog(driver);
                        failedTests.put(config.getTest(), generatedTest);
                        getLog().error("Test failed!");
                    } else {
                        getLog().info("Test passed!");
                    }
                } catch (Exception ex) {
                    failedTests.put(config.getTest(), generatedTest);
                    this.analyzeLog(driver);
                    getLog().error("Test failed!");
                    getLog().error(cleanForMavenLog(ex.getMessage()));
                } finally {
                    driver.quit();
                }
            }
        } catch (ProjectBuildingException | IOException e) {
            throw new MojoExecutionException("Failed to build project structure", e);
        } catch (Exception e) {
            //TODO refine this, possibly remove
            throw new MojoExecutionException("Failed on test server start", e);
        }

        if (failedTests.isEmpty()) {
            getLog().info("All tests were passed successfully!");
        } else {
            failedTests.forEach((name, startupHtmlFile) -> getLog().error(String.format("Test %s failed, please try manually %s", name, startupHtmlFile)));
            throw new MojoFailureException("At least one test failed");
        }
    }

    private WebDriver createBrowser() throws MojoExecutionException {
        if ("chrome".equalsIgnoreCase(webdriver)) {
            ChromeOptions chromeOptions = new ChromeOptions();
            chromeOptions.setHeadless(true);
            LoggingPreferences loggingPreferences = new LoggingPreferences();
            loggingPreferences.enable(LogType.BROWSER, Level.ALL);
            chromeOptions.setCapability("goog:loggingPrefs",
                    loggingPreferences);
            WebDriver driver = new ChromeDriver(chromeOptions);
            return driver;
        } else if ("htmlunit".equalsIgnoreCase(webdriver)){
            return new HtmlUnitDriver(BrowserVersion.BEST_SUPPORTED, true);
        }

        throw new MojoExecutionException("webdriver type not found: " + webdriver);
    }

    private void analyzeLog(WebDriver driver) {
        if (driver != null) {
            driver.manage().logs().get(LogType.BROWSER).getAll().forEach(l -> {
                    if (Level.SEVERE.equals(l.getLevel())) {
                        getLog().error(cleanForMavenLog(l.getMessage()));
                    } else {
                        getLog().info(cleanForMavenLog(l.getMessage()));
                    }
                });
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
    private String cleanForMavenLog(String input) {
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

    @Override
    public String getLanguageOut() {
        return languageOut;
    }

    @Override
    public DependencyOptions.DependencyMode getDependencyMode() {
        return DependencyOptions.DependencyMode.valueOf(dependencyMode);
    }

    @Override
    public boolean getCheckAssertions() {
        return checkAssertions;
    }

    @Override
    public boolean getRewritePolyfills() {
        return rewritePolyfills;
    }

    @Override
    public boolean getSourcemapsEnabled() {
        return enableSourcemaps;
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
            if (getDependencyMode() == DependencyOptions.DependencyMode.SORT_ONLY) {
                return Collections.emptyList();
            }
            return Collections.singletonList("javatests." + test + "_AdapterSuite");
        }

        @Override
        public DependencyOptions.DependencyMode getDependencyMode() {
            return wrapped.getDependencyMode();
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
            return wrapped.getInitialScriptFilename().substring(0, wrapped.getInitialScriptFilename().lastIndexOf(".js")) + "-" + test + ".js";
        }

        @Override
        public String getCompilationLevel() {
            return wrapped.getCompilationLevel();
        }

        @Override
        public String getLanguageOut() {
            return wrapped.getLanguageOut();
        }

        @Override
        public boolean getCheckAssertions() {
            return wrapped.getCheckAssertions();
        }

        @Override
        public boolean getRewritePolyfills() {
            return wrapped.getRewritePolyfills();
        }

        @Override
        public boolean getSourcemapsEnabled() {
            return wrapped.getSourcemapsEnabled();
        }

        public String getTest() {
            return test;
        }
    }
}
