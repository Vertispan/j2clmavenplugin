package com.vertispan.j2cl.mojo;

import com.vertispan.j2cl.build.*;
import net.cardosi.mojo.Versions;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.model.Plugin;
import org.apache.maven.model.PluginExecution;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugin.PluginParameterExpressionEvaluator;
import org.apache.maven.plugin.descriptor.PluginDescriptor;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.DefaultProjectBuildingRequest;
import org.apache.maven.project.MavenProject;
import org.apache.maven.project.ProjectBuildingRequest;
import org.codehaus.plexus.component.configurator.expression.ExpressionEvaluator;
import org.codehaus.plexus.util.xml.Xpp3Dom;

import java.io.File;
import java.io.IOException;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Attempts to do the setup for various test and build goals declared in the current project or in child projects,
 * but also allows the configuration for this goal to further customize them. For example, this goal will be
 * configured to use a particular compilation level, or directory to copy output to.
 */
@Mojo(name = "watch", requiresDependencyResolution = ResolutionScope.TEST, aggregator = true)
public class WatchMojo extends AbstractBuildMojo {

    @Parameter(defaultValue = "${reactorProjects}", required = true, readonly = true)
    protected List<MavenProject> reactorProjects;

    @Parameter(defaultValue = "${j2cl.webappDirectory}")
    protected String webappDirectory;// technically required, but we have logic to test this in execute()

    @Parameter(defaultValue = "${project.build.directory}/${project.build.finalName}", readonly = true)
    protected String defaultWebappDirectory;

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

    /**
     * Whether or not to leave Java assert checks in the compiled code. In j2cl:watch, defaults to true. Has no
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

    @Parameter(defaultValue = "false")
    protected boolean enableSourcemaps;

    @Deprecated
    @Parameter(defaultValue = "SORT_ONLY")
    protected String dependencyMode;

    @Parameter
    protected Map<String, String> taskMappings = new HashMap<>();

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        PluginDescriptor pluginDescriptor = (PluginDescriptor) getPluginContext().get("pluginDescriptor");
        String pluginVersion = pluginDescriptor.getVersion();

        if (webappDirectory == null) {
            if (reactorProjects.size() == 1) {
                // not actually a reactor project, so we'll use the default
                getLog().info("Using " + defaultWebappDirectory + " as webappDirectory since goal is running in a non-reactor build and none was set");
                webappDirectory = defaultWebappDirectory;
            } else {
                getLog().error("No webappDirectory parameter was set. This should be defined in the parent pom (or passed as a property with name 'j2cl.webappDirectory') so that any j2cl module knows where to put its output");
                throw new MojoFailureException("No webappDirectory parameter was set - this should be defined so that any j2cl module knows where to put its output");
            }
        }

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
                getFileWithMavenCoords(bootstrapJsZip)
        );

        // accumulate configs and defaults, provide a lambda we can read dot-separated values from
        ExpressionEvaluator expressionEvaluator = new PluginParameterExpressionEvaluator(mavenSession, mojoExecution);

        ProjectBuildingRequest request = new DefaultProjectBuildingRequest(mavenSession.getProjectBuildingRequest());


        // Each goal in each project has a unique task registry and build service, but
        // they share disk cache and task scheduler between them. This means that only
        // the project in which j2cl:watch is running can define the thread count and
        // cache dir (plus a few other special things like webappDirectory), but the
        // other config options come from the plugin or goal config itself
        final DiskCache diskCache;
        try {
            diskCache = new DefaultDiskCache(gwt3BuildCacheDir);
        } catch (IOException ioException) {
            throw new MojoExecutionException("Failed to create cache", ioException);
        }
        ScheduledExecutorService executor = Executors.newScheduledThreadPool(getWorkerTheadCount());
        TaskScheduler taskScheduler = new TaskScheduler(executor, diskCache);

        // TODO support individual task registries per execution
        TaskRegistry taskRegistry = new TaskRegistry(taskMappings);
        BuildService buildService = new BuildService(taskRegistry, taskScheduler, diskCache);
        // TODO end

        // assemble all of the projects we are hoping to run - if we fail in this process, we can't actually start building or watching
        LinkedHashMap<String, Project> builtProjects = new LinkedHashMap<>();

        try {
            for (MavenProject reactorProject : reactorProjects) {
                if (reactorProject.getPackaging().equals("pom")) {
                    // skip the reactor project?
                    continue;
                }
                Plugin plugin = reactorProject.getPlugin(pluginDescriptor.getPlugin().getKey());
                if (plugin != null) {
                    Xpp3Dom pluginConfiguration = (Xpp3Dom) plugin.getConfiguration();
                    List<PluginExecution> executions = plugin.getExecutions();
                    for (PluginExecution execution : executions) {
                        // merge the configs
                        // config precedence, higher wins:
                        // * explicit config in the execution/goal we found (do not need to interpolate properties here)
                        Xpp3Dom executionConfig = (Xpp3Dom) execution.getConfiguration();
                        // * normally the defaults of the goal we found would be about here, but we skip this
                        // * actual defaults that can't be overridden further below, should have been read from defaults
                        String initialScriptFilename = reactorProject.getArtifactId() + "/" + reactorProject.getArtifactId() + ".js";
                        // * explicit config for the watch goal currently running (with initialScriptFilename written here)
                        Xpp3Dom watchGoalConfig = new Xpp3Dom(mojoExecution.getConfiguration());
                        if (watchGoalConfig.getChild("initialScriptFilename") != null) {
                            watchGoalConfig.getChild("initialScriptFilename").setValue(initialScriptFilename);
                        } else {
                            Xpp3Dom child = new Xpp3Dom("initialScriptFilename");
                            child.setValue(initialScriptFilename);
                            watchGoalConfig.addChild(child);
                        }
                        // * default value for the watch goal currently running
                        plugin.getConfiguration();//necessary?

                        //both of these are wrong, we are using j2cl:watch as the template in the first, not j2cl:build/test, and the second has no defaults...
//                        Xpp3Dom configuration = merge(mojoExecution.getConfiguration(), merge(pluginConfiguration, (Xpp3Dom) execution.getConfiguration()));
                        Xpp3Dom configuration = merge(watchGoalConfig, executionConfig);
                        // wire up the given goals based on the provided configuration
                        for (String goal : execution.getGoals()) {
                            if (goal.equals("test") && shouldCompileTest()) {
                                System.out.println("Test watch temporarily disabled");
                            } else if (goal.equals("build") && shouldCompileBuild()) {
                                System.out.println("Found build " + execution);

                                Xpp3DomConfigValueProvider config = new Xpp3DomConfigValueProvider(configuration, expressionEvaluator, repoSession, repositories, repoSystem, extraClasspath, extraJsZips);
                                Project p = buildProject(reactorProject, reactorProject.getArtifact(), true, projectBuilder, request, pluginVersion, builtProjects, Artifact.SCOPE_COMPILE_PLUS_RUNTIME, getDependencyReplacements());

                                System.out.println(config);
                                String compilationLevel = config.findNode("compilationLevel").readString();
                                String outputTask = getOutputTask(compilationLevel);

                                //TODO support local taskMappings per-execution
//                                Map<String, String> outputToNameMappings = config.findNode("taskMappings").getChildren().stream().collect(Collectors.toMap(PropertyTrackingConfig.ConfigValueProvider.ConfigNode::getName, PropertyTrackingConfig.ConfigValueProvider.ConfigNode::readString));
//                                TaskRegistry taskRegistry = new TaskRegistry(outputToNameMappings);
//                                BuildService buildService = new BuildService(taskRegistry, taskScheduler, diskCache);
                                buildService.assignProject(p, outputTask, config);
                            }
                        }
                    }
                }
            }
        } catch (Exception ex) {
            throw new MojoExecutionException("Failed to build project model", ex);
        }
        WatchService watchService = new WatchService(buildService, executor);
        try {
            // trigger initial changes, and start up watching for future ones to rebuild
            watchService.watch(builtProjects.values().stream().filter(Project::hasSourcesMapped).collect(Collectors.toMap(Function.identity(), p -> p.getSourceRoots().stream().map(Paths::get).collect(Collectors.toList()))));
        } catch (IOException ioException) {
            throw new MojoExecutionException("Error when watching projects", ioException);
        }
        try {
            Thread.sleep(24 * 60 * 60 * 1000);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    protected boolean shouldCompileTest() {
        return true;
    }
    protected boolean shouldCompileBuild() {
        return true;
    }
}
