package net.cardosi.mojo;

import net.cardosi.mojo.cache.CachedProject;
import net.cardosi.mojo.cache.DiskCache;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.ArtifactUtils;
import org.apache.maven.artifact.resolver.filter.ScopeArtifactFilter;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.BuildPluginManager;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugin.descriptor.PluginDescriptor;
import org.apache.maven.plugins.annotations.*;
import org.apache.maven.project.*;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.aether.resolution.ArtifactRequest;
import org.eclipse.aether.resolution.ArtifactResolutionException;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

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
@Mojo(name = "transpile", requiresDependencyResolution = ResolutionScope.COMPILE, aggregator = true)
//@Execute(phase = LifecyclePhase.PROCESS_CLASSES)
public class ExternalTranspileMojo extends AbstractMojo {
    @Parameter( defaultValue = "${session}", readonly = true )
    protected MavenSession mavenSession;

    @Component
    protected BuildPluginManager pluginManager;

    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    protected MavenProject project;

    @Component
    protected ProjectBuilder projectBuilder;

    @Parameter(defaultValue = "${reactorProjects}", required = true, readonly = true)
    protected List<MavenProject> reactorProjects;

    @Parameter(defaultValue = "${project.build.directory}/jsZipCache", required = true, property = "j2cl.jsZip.cache")
    protected File jsZipCacheDir;

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

    @Parameter(defaultValue = "com.vertispan.j2cl:javac-bootstrap-classpath:0.2-SNAPSHOT", required = true)
    protected String javacBootstrapClasspathJar;

    @Parameter(defaultValue = "com.vertispan.j2cl:jre:0.2-SNAPSHOT", required = true)
    protected String jreJar;
    @Parameter(defaultValue = "com.vertispan.j2cl:jre:zip:jszip:0.2-SNAPSHOT", required = true)
    protected String jreJsZip;

    @Parameter(defaultValue = "com.vertispan.j2cl:bootstrap:zip:jszip:0.2-SNAPSHOT", required = true)
    protected String bootstrapJsZip;

    @Parameter(defaultValue = "com.vertispan.j2cl:closure-test:zip:jszip:0.2-SNAPSHOT", required = true)
    protected String testJsZip;

    @Parameter(defaultValue = "com.vertispan.j2cl:gwt-internal-annotations:0.2-SNAPSHOT", required = true)
    protected String internalAnnotationsJar;

    @Parameter(defaultValue = "com.google.jsinterop:jsinterop-annotations:HEAD-SNAPSHOT", required = true)
    protected String jsinteropAnnotationsJar;


    // tools to resolve dependencies
    @Component
    protected RepositorySystem repoSystem;

    @Parameter( defaultValue = "${repositorySystemSession}", readonly = true, required = true )
    protected RepositorySystemSession repoSession;

    @Parameter( defaultValue = "${project.remoteProjectRepositories}", readonly = true, required = true )
    protected List<RemoteRepository> repositories;

    protected boolean forceRecompile = false;//TODO support this so you can see the rest of the exception when resuming later?
    //TODO alternatively, write failure logs to the fail marker file so we can print them again

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

        DiskCache diskCache = new DiskCache(pluginVersion, jsZipCacheDir, getFileWithMavenCoords(javacBootstrapClasspathJar), extraClasspath, extraJsZips);
        diskCache.takeLock();
        ProjectBuildingRequest request = new DefaultProjectBuildingRequest(mavenSession.getProjectBuildingRequest());

        // for each project in the reactor, check if it is an app we should compile
        // TODO how do we want to pick which one(s) are actual apps?
        LinkedHashMap<Artifact, CachedProject> projects = new LinkedHashMap<>();
        List<CachedProject> apps = new ArrayList<>();

        try {
            if (reactorProjects.size() == 1) {
                MavenProject reactorProject = reactorProjects.get(0);
                apps.add(loadDependenciesIntoCache(reactorProject.getArtifact(), reactorProject, projectBuilder, request, diskCache, pluginVersion, projects, classpathScope, "* "));
            } else {

                // TODO better heuristic for j2cl apps in reactor, like reading plugin config in the proj?
                for (MavenProject reactorProject : reactorProjects) {
                    if (reactorProject.getArtifactId().endsWith("-j2cl")) {
                        // Load up all the dependencies in the requested scope for the current project
                        CachedProject p = loadDependenciesIntoCache(reactorProject.getArtifact(), reactorProject, projectBuilder, request, diskCache, pluginVersion, projects, classpathScope, "* ");
                        apps.add(p);
                    }
                }
            }
        } catch (ProjectBuildingException | IOException e) {
            throw new MojoExecutionException("Failed to build project structure", e);
        }
        diskCache.release();

        apps.forEach(p -> {
            p.registerForScope(classpathScope);
        });

//        try {
//            Thread.sleep(TimeUnit.MINUTES.toMillis(10));
//        } catch (InterruptedException e) {
//            e.printStackTrace();
//        }

    }

    private File getFileWithMavenCoords(String coords) throws MojoExecutionException {
        ArtifactRequest request = new ArtifactRequest()
                .setRepositories(repositories)
                .setArtifact(new DefaultArtifact(coords));

        try {
            return repoSystem.resolveArtifact(repoSession, request).getArtifact().getFile();
        } catch (ArtifactResolutionException e) {
            throw new MojoExecutionException("Failed to find artifact" + coords, e);
        }
    }

    private static CachedProject loadDependenciesIntoCache(
            Artifact artifact,
            MavenProject currentProject,
            ProjectBuilder projectBuilder,
            ProjectBuildingRequest projectBuildingRequest,
            DiskCache diskCache,
            String pluginVersion,
            Map<Artifact, CachedProject> seen,
            String classpathScope,
            String depth) throws ProjectBuildingException, IOException {
//        System.out.println(depth + artifact);

        if (seen.containsKey(artifact)) {
//            System.out.println("  " + depth + "already seen");
            return seen.get(artifact);
        }

        List<CachedProject> children = new ArrayList<>();

        // stable ordering so hashing makes sense
        //TODO dedup classifiers? probably not, we want to build separately
        for (Artifact dependency : currentProject.getArtifacts().stream().sorted().collect(Collectors.toList())) {
            // if it shouldnt be on the classpath, skip it, if it isn't part of the current expected scope, skip it
            if (!dependency.getArtifactHandler().isAddedToClasspath()) {
                System.out.println(artifact + " dependency isn't added to classpath " + dependency);
                continue;
            } else if (!new ScopeArtifactFilter(classpathScope).include(dependency)) {
//                System.out.println("  " + depth + "dependency isn't in " + classpathScope + " scope " + dependency);
                continue;
            }
//            System.out.println("\t" + artifact + " depends on " + dependency);

            // make a project to reference for this dependency, if possible
            //TODO handle the case where a jar is referenced without a pom by treating it as having no dependencies

            MavenProject inReactor = getReferencedProject(currentProject, dependency);
            if (inReactor != null) {
//                System.out.println("Found project in reactor matching this " + inReactor);

//                Artifact replacementArtifact = new org.apache.maven.artifact.DefaultArtifact(
//                        dependency.getGroupId(),
//                        dependency.getArtifactId(),
//                        dependency.getVersion(),
//                        dependency.getScope(),
//                        dependency.getType(),
//                        null,
//                        dependency.getArtifactHandler()
//                );
                CachedProject transpiledDep = loadDependenciesIntoCache(dependency, inReactor, projectBuilder, projectBuildingRequest, diskCache, pluginVersion, seen, Artifact.SCOPE_COMPILE, "  " + depth);
                children.add(transpiledDep);
            } else {
                // non-reactor project, build a project for it
//                System.out.println("Creating project from artifact " + dependency);
                projectBuildingRequest.setProject(null);
                projectBuildingRequest.setResolveDependencies(true);
                MavenProject p = projectBuilder.build(dependency, true, projectBuildingRequest).getProject();
                CachedProject transpiledDep = loadDependenciesIntoCache(dependency, p, projectBuilder, projectBuildingRequest, diskCache, pluginVersion, seen, Artifact.SCOPE_COMPILE, "  " + depth);
                children.add(transpiledDep);
            }
        }

        // construct an entry in the project, stick it in the map
        CachedProject p = new CachedProject(diskCache, artifact, currentProject, children);
        seen.put(artifact, p);
        if (p.getArtifactKey().startsWith("com.google.jsinterop:base")) {
            //we have a workaround for now
            p.setIgnoreJavacFailure(true);
        }
        p.markDirty();

        return p;
    }

    private static MavenProject getReferencedProject(MavenProject p, Artifact artifact) {
        String key = ArtifactUtils.key(artifact.getGroupId(), artifact.getArtifactId(), artifact.getBaseVersion());
        MavenProject reference = p.getProjectReferences().get(key);
        if (reference != null && reference.getExecutionProject() != null) {
            reference = reference.getExecutionProject();
        }
        return reference;
    }

}
