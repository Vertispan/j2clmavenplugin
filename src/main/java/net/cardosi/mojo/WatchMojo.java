package net.cardosi.mojo;

import com.google.javascript.jscomp.DependencyOptions;
import net.cardosi.mojo.cache.CachedProject;
import net.cardosi.mojo.cache.DiskCache;
import net.cardosi.mojo.cache.TranspiledCacheEntry;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.model.Plugin;
import org.apache.maven.model.PluginExecution;
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
import org.codehaus.plexus.util.xml.Xpp3Dom;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Attempts to do the setup for various test and build goals declared in the current project or in child projects,
 * but also allows the configuration for this goal to further customize them. For example, this goal will be
 * configured to use a particular compilation level, or directory to copy output to.
 */
@Mojo(name = "watch", requiresDependencyResolution = ResolutionScope.TEST, aggregator = true)
public class WatchMojo extends AbstractBuildMojo {

    @Parameter(defaultValue = "${reactorProjects}", required = true, readonly = true)
    protected List<MavenProject> reactorProjects;


    @Parameter
    protected String webappDirectory;

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
    @Parameter(defaultValue = CachedProject.BUNDLE_JAR, property = "compilationLevel")
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
                getLog().error("No webappDirectory parameter was set - this should be defined in the parent pom so that any j2cl module knows where to put its output");
                throw new MojoFailureException("No webappDirectory parameter was set - this should be defined in the parent pom so that any j2cl module knows where to put its output");
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

        DiskCache diskCache = new DiskCache(pluginVersion, gwt3BuildCacheDir, getFileWithMavenCoords(javacBootstrapClasspathJar), extraClasspath, extraJsZips);
        diskCache.takeLock();
        ProjectBuildingRequest request = new DefaultProjectBuildingRequest(mavenSession.getProjectBuildingRequest());

        // for each project in the reactor, check if it is an app we should compile
        // TODO how do we want to pick which one(s) are actual apps?
        LinkedHashMap<String, CachedProject> projects = new LinkedHashMap<>();
        List<CachedProject> apps = new ArrayList<>();
        List<CompletableFuture<?>> futures = new ArrayList<>();

        try {

            for (MavenProject reactorProject : reactorProjects) {
                if (reactorProject.getPackaging().equals("pom")) {
                    //skip the reactor project?
                    continue;
                }
                Plugin plugin = reactorProject.getPlugin(pluginDescriptor.getPlugin().getKey());
                if (plugin != null) {
                    Xpp3Dom pluginConfiguration = (Xpp3Dom) plugin.getConfiguration();
                    List<PluginExecution> executions = plugin.getExecutions();
                    for (PluginExecution execution : executions) {
                        // merge the configs
                        Xpp3Dom configuration = merge(pluginConfiguration, (Xpp3Dom) execution.getConfiguration());
                        // wire up the given goals based on the provided configuration
                        for (String goal : execution.getGoals()) {
                            if (goal.equals("test") && shouldCompileTest()) {
                                System.out.println("Test watch temporarily disabled");
//                                System.out.println("Found test " + execution);
//                                XmlDomClosureConfig config = new XmlDomClosureConfig(configuration, Artifact.SCOPE_TEST, compilationLevel, rewritePolyfills, reactorProject.getArtifactId(), webappDirectory);
//                                CachedProject source = loadDependenciesIntoCache(reactorProject.getArtifact(), reactorProject, true, projectBuilder, request, diskCache, pluginVersion, projects, Artifact.SCOPE_TEST, getDependencyReplacements(), "* ");
//
//                                // given that set of tasks, we'll chain one more on the end, and watch _that_ for changes
//                                List<CachedProject> children = new ArrayList<>(source.getChildren());
//                                children.add(source);
//                                CachedProject e = new CachedProject(diskCache, reactorProject.getArtifact(), reactorProject, children, reactorProject.getTestCompileSourceRoots(), reactorProject.getTestResources());
//
//                                TestMojo.getTestConfigs(config, Collections.emptyList(), reactorProject,
//                                        //TODO read these from the config, that XmlDomClosureConfig looks pretty silly now
//                                        Arrays.asList("**/Test*.java", "**/*Test.java", "**/GwtTest*.java"),
//                                        Collections.emptyList()
//                                )
//                                        .stream()
//                                        .map(c -> {
//                                    if (c.getCompilationLevel().equalsIgnoreCase(CachedProject.BUNDLE_JAR)) {
//                                        return e.registerAsChunkedApp(config);
//                                    }
//                                    return e.registerAsApp(config);
//                                })
//                                        .forEach(futures::add);
//
//                                apps.add(e);
                            } else if (goal.equals("build") && shouldCompileBuild()) {
                                System.out.println("Found build " + execution);
                                XmlDomClosureConfig config = new XmlDomClosureConfig(configuration, Artifact.SCOPE_COMPILE_PLUS_RUNTIME, compilationLevel, languageOut, rewritePolyfills, reactorProject.getArtifactId(), DependencyOptions.DependencyMode.SORT_ONLY, enableSourcemaps, webappDirectory);

                                // Load up all the dependencies in the requested scope for the current project
                                CachedProject p = loadDependenciesIntoCache(reactorProject.getArtifact(), reactorProject, true, projectBuilder, request, diskCache, pluginVersion, projects, Artifact.SCOPE_COMPILE_PLUS_RUNTIME, getDependencyReplacements(), "* ");

                                CompletableFuture<TranspiledCacheEntry> f;
                                if (config.getCompilationLevel().equalsIgnoreCase(CachedProject.BUNDLE_JAR)) {
                                    f = p.registerAsChunkedApp(config);
                                } else {
                                    f = p.registerAsApp(config);
                                }
                                futures.add(f);
                                apps.add(p);
                            }
                        }
                    }
                }
            }
//            }
        } catch (ProjectBuildingException | IOException e) {
            throw new MojoExecutionException("Failed to build project structure", e);
        }
        diskCache.release();

        for (CachedProject app : projects.values()) {
            //TODO instead of N threads per project, combine threads?
            try {
                app.watch();
            } catch (IOException ex) {
                ex.printStackTrace();
                //TODO fall back to polling or another strategy
            }
        }

        // TODO replace this dumb timer with a System.in loop so we can watch for some commands from the user
        try {
            Thread.sleep(TimeUnit.MINUTES.toMillis(30));
        } catch (InterruptedException e) {
            e.printStackTrace();
        }

    }

    private Xpp3Dom merge(Xpp3Dom pluginConfiguration, Xpp3Dom configuration) {
        if (pluginConfiguration == null) {
            if (configuration == null) {
                return new Xpp3Dom("configuration");
            }
            return new Xpp3Dom(configuration);
        } else if (configuration == null) {
            return new Xpp3Dom(pluginConfiguration);
        }
        return Xpp3Dom.mergeXpp3Dom(new Xpp3Dom(configuration), new Xpp3Dom(pluginConfiguration));
    }

    protected boolean shouldCompileTest() {
        return true;
    }
    protected boolean shouldCompileBuild() {
        return true;
    }
}
