package net.cardosi.mojo;

import net.cardosi.mojo.cache.CachedProject;
import net.cardosi.mojo.cache.DiskCache;
import net.cardosi.mojo.cache.TranspiledCacheEntry;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugin.descriptor.PluginDescriptor;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.DefaultProjectBuildingRequest;
import org.apache.maven.project.MavenProject;
import org.apache.maven.project.ProjectBuildingException;
import org.apache.maven.project.ProjectBuildingRequest;
import org.codehaus.plexus.util.xml.Xpp3Dom;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.repository.RemoteRepository;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.CompletableFuture;

@Mojo(name = "watch", requiresDependencyResolution = ResolutionScope.COMPILE, aggregator = true)
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
        LinkedHashMap<Artifact, CachedProject> projects = new LinkedHashMap<>();
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
                Xpp3Dom goalConfiguration = reactorProject.getGoalConfiguration(pluginDescriptor.getGroupId(), pluginDescriptor.getArtifactId(), null, null);
                //TODO what happens when both test and build are defined?
//                    Plugin j2clPlugin = reactorProject.getBuild().getPluginsAsMap().get("net.cardosi.j2cl:j2cl-maven-plugin");
                if (goalConfiguration != null) {
                    // read out the goals/configs and see what scope to use, what other params to use
                    //TODo should not use classpathScope here, but instead see what goal we're staring at
                    XmlDomClosureConfig config = new XmlDomClosureConfig(goalConfiguration, "runtime", compilationLevel, reactorProject.getArtifactId(), defaultWebappDirectory);

                    // Load up all the dependencies in the requested scope for the current project
                    CachedProject p = loadDependenciesIntoCache(reactorProject.getArtifact(), reactorProject, projectBuilder, request, diskCache, pluginVersion, projects, config.getClasspathScope(), "* ");

                    CompletableFuture<TranspiledCacheEntry> f = p.registerAsApp(config);
                    futures.add(f);
                    apps.add(p);
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
}
