package com.vertispan.j2cl.mojo;

import com.gargoylesoftware.htmlunit.BrowserVersion;
import com.google.common.io.CharStreams;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.vertispan.j2cl.build.*;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.model.FileSet;
import org.apache.maven.model.Plugin;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugin.PluginParameterExpressionEvaluator;
import org.apache.maven.plugin.descriptor.PluginDescriptor;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.DefaultProjectBuildingRequest;
import org.apache.maven.project.ProjectBuildingException;
import org.apache.maven.project.ProjectBuildingRequest;
import org.codehaus.plexus.component.configurator.expression.ExpressionEvaluator;
import org.codehaus.plexus.util.xml.Xpp3Dom;
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

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.logging.Level;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Mojo(name = "test", requiresDependencyResolution = ResolutionScope.TEST)
public class TestMojo extends AbstractBuildMojo {
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
    @Parameter(defaultValue = "BUNDLE_JAR", property = "compilationLevel")
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

    /**
     * Closure flag: "Determines the set of builtin externs to load. Options: BROWSER, CUSTOM. Defaults to BROWSER."
     *
     * Presently we default to BROWSER, but are considering changing this to CUSTOM if we include externs files in
     * the generate jsinterop artifacts, so that each set of bindings is self-contained.
     */
    @Parameter(defaultValue = "BROWSER")
    protected String env;

    @Parameter( property = "skipTests", defaultValue = "false" )
    protected boolean skipTests;

    @Parameter( property = "maven.test.skip", defaultValue = "false" )
    protected boolean skip;

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
        if (skip) {
            return;
        }

        Map<String, String> failedTests = new HashMap<>();

        PluginDescriptor pluginDescriptor = (PluginDescriptor) getPluginContext().get("pluginDescriptor");
        String pluginVersion = pluginDescriptor.getVersion();

        Plugin plugin = project.getPlugin(pluginDescriptor.getPlugin().getKey());

        // accumulate configs and defaults, provide a lambda we can read dot-separated values from
        ExpressionEvaluator expressionEvaluator = new PluginParameterExpressionEvaluator(mavenSession, mojoExecution);

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


        Xpp3DomConfigValueProvider config = new Xpp3DomConfigValueProvider(merge((Xpp3Dom) plugin.getConfiguration(), mojoExecution.getConfiguration()), expressionEvaluator, repoSession, repositories, repoSystem, extraClasspath, extraJsZips, getLog());

        ProjectBuildingRequest request = new DefaultProjectBuildingRequest(mavenSession.getProjectBuildingRequest());

        // build project from maven project and dependencies, recursively
        LinkedHashMap<String, Project> builtProjects = new LinkedHashMap<>();
        Project test;
        try {
            // Build the dependency tree for the project itself. Note that this picks up the scope of the test side of things, but uses the app sources, which isn't exactly right.
            // Less wrong would be to just build main normally, and then also add in the tests, the maven-specific buildProject() isnt that smart yet
            Project main = buildProject(project, project.getArtifact(), false, projectBuilder, request, pluginVersion, builtProjects, Artifact.SCOPE_TEST, getDependencyReplacements());

            // Given that set of tasks, we'll chain one more on the end - this is the one that will have the actual test sources+resources. To be fully correct,
            // only this should have the scope=test deps on it
            test = new Project(main.getKey() + "-test");
            List<com.vertispan.j2cl.build.task.Dependency> testDeps = new ArrayList<>(main.getDependencies());
            Dependency mainDep = new Dependency();
            mainDep.setScope(com.vertispan.j2cl.build.task.Dependency.Scope.BOTH);
            mainDep.setProject(main);
            testDeps.add(mainDep);
            test.setDependencies(testDeps);
            test.setSourceRoots(new ArrayList<>(project.getTestCompileSourceRoots()));
            test.getSourceRoots().addAll(project.getTestResources().stream().map(FileSet::getDirectory).collect(Collectors.toList()));
        } catch (ProjectBuildingException e) {
            throw new MojoExecutionException("Failed to build project structure", e);
        }

        // given the build output, determine what tasks we're going to run
        String outputTask = getOutputTask(compilationLevel);

        // construct other required elements to get the work done
        ScheduledExecutorService executor = Executors.newScheduledThreadPool(getWorkerTheadCount());
        final DiskCache diskCache;
        try {
            diskCache = new DefaultDiskCache(getCacheDir().toFile(), executor);
        } catch (IOException ioException) {
            throw new MojoExecutionException("Failed to create cache", ioException);
        }
        MavenLog mavenLog = new MavenLog(getLog());
        TaskScheduler taskScheduler = new TaskScheduler(executor, diskCache, mavenLog);
        TaskRegistry taskRegistry = createTaskRegistry();

        // Given these, build the graph of work we need to complete to get the list of tests
        BuildService buildService = new BuildService(taskRegistry, taskScheduler, diskCache);
        buildService.assignProject(test, "test_summary", config);

        // Get the hash of all current files, since we aren't running a watch service
        buildService.initialHashes();

        // perform the initial build, produce the test
        BlockingBuildListener listener = new BlockingBuildListener();
        try {
            buildService.requestBuild(listener);
            listener.blockUntilFinished();
        } catch (InterruptedException e) {
            throw new MojoExecutionException("Interrupted", e);
        }

        // now we have the test summary json in the webapp dir
        // Grab the JSON that describes all tests, and use it to see which ones we'll actually run (user-specified tests, excludes, includes)
        final String[] generatedTests;
        try (final Reader reader = new BufferedReader(new FileReader(Paths.get(webappDirectory).resolve("test_summary.json").toFile()))) {
            generatedTests = new GsonBuilder().create().
                    <Map<String, String[]>>fromJson(reader, new TypeToken<Map<String, String[]>>() {}.getType())
                    .get("tests");
        } catch (IOException ex) {
            throw new MojoExecutionException("Error reading test_summary.json", ex);
        }

        try {
            //TODO manage the includes/excludes and manually specified test
            for (String generatedTest : generatedTests) {
                // this is pretty hacky, TODO clean this up, put the intermediate js file somewhere nicer
                String testFilePathWithoutSuffix = generatedTest.substring(0, generatedTest.length() - 3);
                File testJs = new File(webappDirectory, testFilePathWithoutSuffix + ".testsuite");
                Path tmp = Files.createTempDirectory(testJs.getName() + "-dir");
                Path copy = tmp.resolve(testFilePathWithoutSuffix + ".js");
                Files.createDirectories(copy.getParent());
                Files.copy(testJs.toPath(), copy);
                String testClass = testFilePathWithoutSuffix.replaceAll("/", ".");

                // Synthesize a new project which only depends on the last one, and only contains the named test's .testsuite content, remade into a one-off JS file
                Project suite = new Project(test.getKey() + "-" + generatedTest);
                suite.setSourceRoots(Collections.singletonList(tmp.toString()));
                ArrayList<com.vertispan.j2cl.build.task.Dependency> dependencies = new ArrayList<>(test.getDependencies());
                Dependency testDep = new Dependency();
                testDep.setProject(test);
                testDep.setScope(com.vertispan.j2cl.build.task.Dependency.Scope.BOTH);
                dependencies.add(testDep);
                suite.setDependencies(dependencies);

                // build this new test project normally
                String testScriptFilename = initialScriptFilename.substring(0, initialScriptFilename.lastIndexOf(".js")) + "-" + testClass + ".js";
                PropertyTrackingConfig.ConfigValueProvider overridenConfig = new OverrideConfigValueProvider(config,
                        Collections.singletonMap(
                                "initialScriptFilename",
                                testScriptFilename
                        )
                );

                // Fresh build service (to avoid re-running other final tasks) since we're building serially,
                // but we reuse the params
                buildService = new BuildService(taskRegistry, taskScheduler, diskCache);
                buildService.assignProject(suite, outputTask, overridenConfig);
                buildService.initialHashes();
                BlockingBuildListener l = new BlockingBuildListener();
                try {
                    buildService.requestBuild(l);
                    l.blockUntilFinished();
                } catch (InterruptedException e) {
                    throw new MojoExecutionException("Interrupted", e);
                } catch (CompletionException e) {
                    throw new MojoExecutionException("Error while building", e.getCause());
                }
                if (!l.isSuccess()) {
                    throw new MojoFailureException("Error building test, see log for details");
                }

                getLog().info("Test started: " + testClass);
                // write a simple html file to that output dir
                //TODO parallelize this - run once each is done, possibly concurrently
                //TODO don't fail on the first test that doesn't work
                Path startupHtmlFile;
                try {
                    Path webappDirPath = Paths.get(webappDirectory);
                    Path outputJsPath = webappDirPath.resolve(testScriptFilename);
                    File outputJs = new File(new File(webappDirectory), testScriptFilename);
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
                    String path = startupHtmlFile.toString().replaceAll(Pattern.quote(File.separator), "/");
                    String url = "http://localhost:" + port + "/" + path;
                    getLog().info("fetching " + url);
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
                        failedTests.put(testClass, generatedTest);
                        getLog().error("Test failed!");
                    } else {
                        getLog().info("Test passed!");
                    }
                } catch (Exception ex) {
                    failedTests.put(testClass, generatedTest);
                    this.analyzeLog(driver);
                    getLog().error("Test failed!");
                    getLog().error(cleanForMavenLog(ex.getMessage()));
                } finally {
                    driver.quit();
                }

            }
        } catch (Exception exception) {
            throw new MojoExecutionException("Failed to run tests", exception);
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
}
