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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * Experiment to transpile a project into the j2cl cache. Before it begins, recursively transpiles
 * dependencies if they are not present in the cache.
 *
 * For consistency, reactor projects will first run source:jar, and then transpile that as external
 * projects have done.
 *
 * Given a g:a:v, first compute its hash, and see if it is present: roughly,
 * hash(j2cl-maven-plugin.version + project.artifacts.map(hash) + source)
 * To do this, we obviously first compute the hashes of all other dependencies recursively. Any
 * item missing from the cache must first be compiled and stored under its hash, then later projects
 * can be hashed and then compiled if needed.
 *
 * The computed cache needs to be shared between instances of this goal and the closure goal, ideally
 * by writing it to disk in some cheap way so it can be read back in easily. Iterating the project
 * model must already be cheap, so maybe we can just rebuild it from scratch each time?
 */
@Mojo(name = "build", requiresDependencyResolution = ResolutionScope.COMPILE)
//@Execute(phase = LifecyclePhase.PROCESS_CLASSES)
public class BuildMojo extends AbstractGwt3BuildMojo implements ClosureBuildConfiguration {

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
    @Parameter(defaultValue = Artifact.SCOPE_RUNTIME, required = true)
    protected String classpathScope;

    @Parameter(defaultValue = "${project.artifactId}/${project.artifactId}.js", required = true)
    protected String initialScriptFilename;

    @Parameter(defaultValue = "${project.build.directory}/${project.build.finalName}", required = true)
    protected String webappDirectory;

    @Parameter
    protected List<String> externs = new ArrayList<>();

    @Parameter
    protected List<String> entrypoint = new ArrayList<>();

    @Parameter(defaultValue = "ADVANCED")
    protected String compilationLevel;

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
                getFileWithMavenCoords("javax.annotation:jsr250-api:1.0"),
                getFileWithMavenCoords("com.vertispan.jsinterop:base:1.0.0-SNAPSHOT")//TODO stop hardcoding this when goog releases a "base" which actually works on both platforms
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
        LinkedHashMap<Artifact, CachedProject> projects = new LinkedHashMap<>();

        try {
            CachedProject e = loadDependenciesIntoCache(project.getArtifact(), project, projectBuilder, request, diskCache, pluginVersion, projects, classpathScope, "* ");
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
    public List<String> getExterns() {
        return externs;
    }

    @Override
    public String getClasspathScope() {
        return classpathScope;
    }

    @Override
    public String getCompilationLevel() {
        return compilationLevel;
    }
}
