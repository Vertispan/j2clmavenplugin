package net.cardosi.mojo;

import com.google.javascript.jscomp.CompilerOptions;
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
 *     DEPRECATED: Entrypoints can be specified by their JS name, indicating where the compiler should start when
 *     pruning and optimizing the output. This should either be a plain JS/Closure module, or should be a
 *     Java class with a matching .native.js to instantiate the class on startup. This feature will be removed
 *     soon, but may support being re-added through extra closure-compiler flags.
 * </p>
 * <p>
 *     Closure defines (or J2cl/GWT System Properties) can be provided as well. If plugin configuration
 *     doesn't provide them, then defaults are set following <a href="https://github.com/google/j2cl/blob/master/docs/best-practices.md#closure-compiler-flags">
 *     J2cl's best practices</a>, which as of writing is just setting `goog.DEBUG` to `false. Internally,
 *     `jre.checkedMode` will be set to DISABLED when this is done, but it can be overridden. Here are
 *     some other defines that might make sense to be configured to further reduce output size, but please
 *     be sure to check documentation before using them to understand what effects they have.
 * </p>
 * <ul>
 *     <li>
 *         jre.checkedMode - this can be ENABLED or DISABLED, defaults to DISABLED when goog.DEBUG is false,
 *         or ENABLED when goog.DEBUG is true. This has many impacts inside of GWT's JRE emulation.
 *     </li>
 *     <li>
 *         jre.checks.checkLevel - this can be NORMAL, OPTIMIZED, and MINIMAL, and defaults to NORMAL.
 *         Within the JRE emulation, this is then used to decide how many checks to perform at runtime.
 *         Reducing this level may make some code faster or slightly smaller, but at the risk of some
 *         expected JRE exceptions no longer being thrown. Consult GWT's JRE emulation implementation
 *         or J2CL's best practices link above to wee what specific effects this may have.
 *     </li>
 *     <li>
 *         jsinterop.checks - This can be DISABLED or ENABLED. If ENABLED, some checks in jsinterop-base
 *         will result in ClassCastExceptions if a type isn't what is expected, while if set to DISABLED,
 *         those errors will be an AssertionError instead if jre.checked is enabled, or no failure at all
 *         if it is disabled.
 *     </li>
 * </ul>
 * <p>
 *     Some other links other defines that are set within J2CL and jsinterop:
 * </p>
 * <ul>
 *     <li>https://github.com/google/j2cl/blob/fb66a0d/jre/java/java/lang/jre.js</li>
 *     <li>https://github.com/google/jsinterop-base/blob/18973cb/java/jsinterop/base/jsinterop.js#L25-L28</li>
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

    @Deprecated
    @Parameter
    protected List<String> entrypoint = new ArrayList<>();

    /**
     * Describes how the output should be built - presently supports five modes, four of which are closure-compiler
     * "compilationLevel" argument options, and an additional special case for J2cl-base applications. The quoted
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
    @Parameter(defaultValue = "ADVANCED_OPTIMIZATIONS", property = "compilationLevel")
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
     * Closure flag: "Rewrite ES6 library calls to use polyfills provided by the compiler's runtime."
     * Unlike in closure-compiler, defaults to false.
     */
    @Parameter(defaultValue = "false")
    protected boolean rewritePolyfills;

    /**
     * Whether or not to leave Java assert checks in the compiled code. In j2cl:build, defaults to true. Has no
     * effect when the compilation level isn't set to ADVANCED_OPTIMIZATIONS, assertions will always remain
     * enabled.
     */
    @Parameter(defaultValue = "false")
    protected boolean checkAssertions;

    @Deprecated
    @Parameter(defaultValue = "SORT_ONLY")
    protected String dependencyMode;

    @Parameter(defaultValue = "false")
    protected boolean enableSourcemaps;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        if (!getEntrypoint().isEmpty()) {
            getLog().warn("WARNING!");
            getLog().warn("WARNING!");
            getLog().warn("WARNING!");
            getLog().warn("<entrypoint> support will be removed soon, please remove this from your configuration. See https://github.com/Vertispan/j2clmavenplugin/issues/26 for discussion on this.");
            getLog().warn("WARNING!");
            getLog().warn("WARNING!");
            getLog().warn("WARNING!");
        }
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

        try {
            CachedProject e = loadDependenciesIntoCache(project.getArtifact(), project, false, projectBuilder, request, diskCache, pluginVersion, projects, Artifact.SCOPE_COMPILE_PLUS_RUNTIME, getDependencyReplacements(), "* ");
            diskCache.release();
            if (getCompilationLevel().equalsIgnoreCase(CachedProject.BUNDLE_JAR)) {
                e.registerAsChunkedApp(this).join();
            } else {
                e.registerAsApp(this).join();
            }
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
    public DependencyOptions.DependencyMode getDependencyMode() {
        return DependencyOptions.DependencyMode.valueOf(dependencyMode);
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
    public String getLanguageOut() {
        return languageOut;
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
}
