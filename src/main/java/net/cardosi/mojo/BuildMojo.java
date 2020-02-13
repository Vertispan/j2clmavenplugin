package net.cardosi.mojo;

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

import java.io.File;
import java.io.IOException;
import java.util.*;

/**
 * <p>
     * Transpiles this project and all of its dependencies, then combines them all into a single JS
     * executable.
 * </p>
 * <p>
 *     Results are cached based on their sources and the soruces of their dependencies, and the final
 *     output is copied to the specified location by the build configuration. For the project this goal
 *     is executed on, it will have its annotation processors run, then strip {@code @GwtIncompatible}
 *     from all sources. These stripped sources then will be transpiled with j2cl, then optimized with
 *     the Closure Compiler. Dependencies on the other hand, even ones in the current reactor (unlike
 *     with the watch goal) will be built from their artifact, so sources should be included in some
 *     form.
 * </p>
 * <p>
 *     The output defaults to assume that output should be generated into the same directory that would
 *     be used if a war were being generated, through the {@link BuildMojo#webappDirectory} parameter.
 *     Then, in there, the {@link BuildMojo#initialScriptFilename} specifies the path to the initial
 *     JS output file. This defaults to the current project's artifactId as a new directory, then inside
 *     of there the main js file is named for the artifactId again, with a ".js" suffix. This is to keep
 *     other generated output (split points, resources, sourcemaps) from cluttering up the main war.
 *     Unfortunately, it also requires that the HTML page which loads the script start in that directory
 *     in order for Chrome's sourcemap implementation to work correctly.
 * </p>
 * <p>
 *     Entrypoints can be specified by their JS name, indicating where the compiler should start when
 *     pruning and optimizing the output. This should either be a plain JS/Closure module, or should be a
 *     Java class with a matching .native.js to instantiate the class on startup.
 * </p>
 * <p>
 *     Closure defines (or J2cl/GWT System Properties) can be provided as well. There are some defaults,
 *     assuming that a production app should be built to be as small as possible, with many checks turned off:
 * </p>
 * <ul>
 *     <li>jre.checkedMode=DISABLED</li>
 *     <li>jre.checks.checkLevel=MINIMAL</li>
 *     <li>jsinterop.checks=DISABLED</li>
 *     <li>goog.DEBUG=false</li>
 * </ul>
 */
@Mojo(name = "build", requiresDependencyResolution = ResolutionScope.COMPILE_PLUS_RUNTIME)
//@Execute(phase = LifecyclePhase.PROCESS_CLASSES)
public class BuildMojo extends AbstractBuildMojo implements ClosureBuildConfiguration {

    /**
     * The scope to use when picking dependencies to pass to the Closure Compiler.
     * <p>The scope should be one of the scopes defined by org.apache.maven.artifact.Artifact. This includes the following:
     * <ul>
     * <li><i>compile</i> - system, provided, compile
     * <li><i>runtime</i> - compile, runtime
     * <li><i>compile+runtime</i> - system, provided, compile, runtime
     * <li><i>runtime+system</i> - system, compile, runtime
     * <li><i>test</i> - system, provided, compile, runtime, test
     * </ul>
     */
    @Parameter(defaultValue = Artifact.SCOPE_RUNTIME, required = true)
    protected String classpathScope;

    @Parameter(defaultValue = "${project.artifactId}/${project.artifactId}.js", required = true)
    protected String initialScriptFilename;

    @Parameter(defaultValue = "${project.build.directory}/${project.build.finalName}", required = true)
    protected String webappDirectory;

    @Parameter
    protected Set<String> externs = new TreeSet<>();

    @Parameter
    protected List<String> entrypoint = new ArrayList<>();

    @Parameter(defaultValue = "ADVANCED")
    protected String compilationLevel;

    @Parameter
    protected Map<String, String> defines = new TreeMap<>();

    /**
     * Closure flag: "Rewrite ES6 library calls to use polyfills provided by the compiler's runtime."
     * Unlike in closure-compiler, defaults to false.
     */
    @Parameter(defaultValue = "false")
    protected boolean rewritePolyfills;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        PluginDescriptor pluginDescriptor = (PluginDescriptor) getPluginContext().get("pluginDescriptor");
        String pluginVersion = pluginDescriptor.getVersion();

        //TODO need to be very careful about allowing these to be configurable, possibly should tie them to the "plugin version" aspect of the hash
        //     or stitch them into the module's dependencies, that probably makes more sense...
        List<File> extraClasspath = Arrays.asList(
                getFileWithMavenCoords(jreJar),
                getFileWithMavenCoords(internalAnnotationsJar),
                getFileWithMavenCoords(jsinteropAnnotationsJar),
                getFileWithMavenCoords("com.vertispan.jsinterop:base:" + Versions.VERTISPAN_JSINTEROP_BASE_VERSION)//TODO stop hardcoding this when goog releases a "base" which actually works on both platforms
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

        // if key defines aren't set, assume "prod defaults" - need to doc the heck out of this
        defines.putIfAbsent("jre.checkedMode", "DISABLED");
        defines.putIfAbsent("jre.checks.checkLevel", "MINIMAL");
        defines.putIfAbsent("jsinterop.checks", "DISABLED");
        defines.putIfAbsent("goog.DEBUG", "false");

        try {
            CachedProject e = loadDependenciesIntoCache(project.getArtifact(), project, false, projectBuilder, request, diskCache, pluginVersion, projects, Artifact.SCOPE_COMPILE_PLUS_RUNTIME, getDependencyReplacements(), "* ");
            diskCache.release();
            e.registerAsApp(this).join();
        } catch (ProjectBuildingException | IOException e) {
            throw new MojoExecutionException("Failed to build project structure", e);
        }
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
    public List<String> getEntrypoint() {
        return entrypoint;
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
    public String getClasspathScope() {
        return classpathScope;
    }

    @Override
    public String getCompilationLevel() {
        return compilationLevel;
    }

    @Override
    public boolean getRewritePolyfills() {
        return rewritePolyfills;
    }
}
