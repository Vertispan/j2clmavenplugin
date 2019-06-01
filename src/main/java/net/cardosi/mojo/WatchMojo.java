package net.cardosi.mojo;

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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.CompletableFuture;

@Mojo(name = "watch", requiresDependencyResolution = ResolutionScope.TEST, aggregator = true)
//@Execute(phase = LifecyclePhase.PROCESS_CLASSES)
public class WatchMojo extends AbstractGwt3BuildMojo {

    @Parameter(defaultValue = "${reactorProjects}", required = true, readonly = true)
    protected List<MavenProject> reactorProjects;


    @Parameter(required = true)
    protected String defaultWebappDirectory;

    @Parameter(defaultValue = "BUNDLE")
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
        LinkedHashMap<String, CachedProject> projects = new LinkedHashMap<>();
        List<CachedProject> apps = new ArrayList<>();
        List<CompletableFuture<?>> futures = new ArrayList<>();

        try {
//            if (reactorProjects.size() == 1) {
//                MavenProject reactorProject = reactorProjects.get(0);
//                CachedProject e = loadDependenciesIntoCache(reactorProject.getArtifact(), reactorProject, projectBuilder, request, diskCache, pluginVersion, projects, classpathScope, "* ");
//                futures.add(e.registerAsApp(this));
//                apps.add(e);
//            } else {

            for (MavenProject reactorProject : reactorProjects) {
                if (project.equals(reactorProject) || reactorProject.getPackaging().equals("pom")) {
                    //skip the reactor project?
                    continue;
                }
                Plugin plugin = reactorProject.getPlugin(pluginDescriptor.getPlugin().getKey());
                if (plugin != null) {
                    List<PluginExecution> executions = plugin.getExecutions();
                    for (PluginExecution execution : executions) {
                        if (execution.getConfiguration() != null) {
                            // wire up the given goals based on the provided configuration
                            for (String goal : execution.getGoals()) {
                                if (goal.equals("test") && shouldCompileTest()) {
                                    System.out.println("Found test " + execution);
                                    XmlDomClosureConfig config = new XmlDomClosureConfig((Xpp3Dom) execution.getConfiguration(), Artifact.SCOPE_TEST, compilationLevel, reactorProject.getArtifactId(), defaultWebappDirectory);
                                    CachedProject source = loadDependenciesIntoCache(reactorProject.getArtifact(), reactorProject, false, projectBuilder, request, diskCache, pluginVersion, projects, config.getClasspathScope(), "* ");

                                    // given that set of tasks, we'll chain one more on the end, and watch _that_ for changes
                                    List<CachedProject> children = new ArrayList<>(source.getChildren());
                                    children.add(source);
                                    CachedProject e = new CachedProject(diskCache, reactorProject.getArtifact(), reactorProject, children, reactorProject.getTestCompileSourceRoots());

                                    futures.add(e.registerAsApp(config));
                                    apps.add(e);
                                } else if (goal.equals("build") && shouldCompileBuild()) {
                                    System.out.println("Found build " + execution);
                                    XmlDomClosureConfig config = new XmlDomClosureConfig((Xpp3Dom) execution.getConfiguration(), Artifact.SCOPE_COMPILE_PLUS_RUNTIME, compilationLevel, reactorProject.getArtifactId(), defaultWebappDirectory);

                                    // Load up all the dependencies in the requested scope for the current project
                                    CachedProject p = loadDependenciesIntoCache(reactorProject.getArtifact(), reactorProject, true, projectBuilder, request, diskCache, pluginVersion, projects, config.getClasspathScope(), "* ");

                                    CompletableFuture<TranspiledCacheEntry> f = p.registerAsApp(config);
                                    futures.add(f);
                                    apps.add(p);
                                }
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

        // everything below this point is garbage, needs to be rethought
        futures.forEach(CompletableFuture::join);

//        try {
//            Thread.sleep(TimeUnit.MINUTES.toMillis(10));
//        } catch (InterruptedException e) {
//            e.printStackTrace();
//        }

    }

    protected boolean shouldCompileTest() {
        return true;
    }
    protected boolean shouldCompileBuild() {
        return true;
    }
}
