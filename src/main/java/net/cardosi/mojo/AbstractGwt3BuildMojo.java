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
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.apache.maven.project.ProjectBuilder;
import org.apache.maven.project.ProjectBuildingException;
import org.apache.maven.project.ProjectBuildingRequest;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.aether.resolution.ArtifactRequest;
import org.eclipse.aether.resolution.ArtifactResolutionException;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public abstract class AbstractGwt3BuildMojo extends AbstractMojo {
    @Parameter( defaultValue = "${session}", readonly = true )
    protected MavenSession mavenSession;

    @Component
    protected BuildPluginManager pluginManager;

    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    protected MavenProject project;

    @Component
    protected ProjectBuilder projectBuilder;

    @Parameter(defaultValue = "${project.build.directory}/gwt3BuildCache", required = true, property = "gwt3.cache.dir")
    protected File gwt3BuildCacheDir;

    @Parameter(defaultValue = "com.vertispan.j2cl:javac-bootstrap-classpath:0.3-SNAPSHOT", required = true)
    protected String javacBootstrapClasspathJar;

    @Parameter(defaultValue = "com.vertispan.j2cl:jre:0.3-SNAPSHOT", required = true)
    protected String jreJar;
    @Parameter(defaultValue = "com.vertispan.j2cl:jre:zip:jszip:0.3-SNAPSHOT", required = true)
    protected String jreJsZip;

    @Parameter(defaultValue = "com.vertispan.j2cl:bootstrap:zip:jszip:0.3-SNAPSHOT", required = true)
    protected String bootstrapJsZip;

    @Parameter(defaultValue = "com.vertispan.j2cl:closure-test:zip:jszip:0.3-SNAPSHOT", required = true)
    protected String testJsZip;

    @Parameter(defaultValue = "com.vertispan.j2cl:gwt-internal-annotations:0.3-SNAPSHOT", required = true)
    protected String internalAnnotationsJar;

    @Parameter(defaultValue = "com.google.jsinterop:jsinterop-annotations:HEAD-SNAPSHOT", required = true)
    protected String jsinteropAnnotationsJar;

    @Parameter(defaultValue = "com.vertispan.j2cl:junit-annotations:0.3-SNAPSHOT", required = true)
    protected String junitAnnotations;

    @Parameter(defaultValue = "com.google.jsinterop:base,org.realityforge.com.google.jsinterop:base,com.google.gwt:gwt-user,com.google.gwt:gwt-dev,com.google.gwt:gwt-servlet")
    protected List<String> excludedDependencies;


    // tools to resolve dependencies
    @Component
    protected RepositorySystem repoSystem;

    @Parameter( defaultValue = "${repositorySystemSession}", readonly = true, required = true )
    protected RepositorySystemSession repoSession;

    @Parameter( defaultValue = "${project.remoteProjectRepositories}", readonly = true, required = true )
    protected List<RemoteRepository> repositories;


    protected File getFileWithMavenCoords(String coords) throws MojoExecutionException {
        ArtifactRequest request = new ArtifactRequest()
                .setRepositories(repositories)
                .setArtifact(new DefaultArtifact(coords));

        try {
            return repoSystem.resolveArtifact(repoSession, request).getArtifact().getFile();
        } catch (ArtifactResolutionException e) {
            throw new MojoExecutionException("Failed to find artifact" + coords, e);
        }
    }

    protected static CachedProject loadDependenciesIntoCache(
            Artifact artifact,
            MavenProject currentProject,
            boolean lookupReactorProjects,
            ProjectBuilder projectBuilder,
            ProjectBuildingRequest projectBuildingRequest,
            DiskCache diskCache,
            String pluginVersion,
            Map<String, CachedProject> seen,
            String classpathScope,
            List<String> excludedDependencies,
            String depth) throws ProjectBuildingException, IOException {
//        System.out.println(depth + artifact);

        // this is roughly DefaultArtifact.toString, minus scope, since we don't care what the scope is for the purposes of building projects
        String key = artifact.getGroupId() + ":" + artifact.getArtifactId() + ":" + artifact.getBaseVersion();
        if (artifact.getClassifier() != null) {
            key += ":" + artifact.getClassifier();
        }

        boolean replace = false;
        if (seen.containsKey(key)) {
//            System.out.println("  " + depth + "already seen");
            if (!seen.get(key).getMavenProject().getArtifacts().isEmpty() || currentProject.getArtifacts().isEmpty()) {
                return seen.get(key);
            }
            replace = true;
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
            } else if (Artifact.SCOPE_SYSTEM.equals(dependency.getScope())) {
                System.out.println("WARNING: " + artifact + " has a scope=system dependency on " + dependency + ", which will be skipped");
                continue;
            }
//            System.out.println("\t" + artifact + " depends on " + dependency);

            // make a project to reference for this dependency, if possible
            //TODO handle the case where a jar is referenced without a pom by treating it as having no dependencies

            MavenProject inReactor = lookupReactorProjects ? getReferencedProject(currentProject, dependency) : null;
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
                CachedProject transpiledDep = loadDependenciesIntoCache(dependency, inReactor, lookupReactorProjects, projectBuilder, projectBuildingRequest, diskCache, pluginVersion, seen, Artifact.SCOPE_COMPILE_PLUS_RUNTIME, excludedDependencies, "  " + depth);
                children.add(transpiledDep);
            } else {
                // non-reactor project, build a project for it
//                System.out.println("Creating project from artifact " + dependency);
                projectBuildingRequest.setProject(null);
                projectBuildingRequest.setResolveDependencies(true);
                projectBuildingRequest.setRemoteRepositories(null);
                MavenProject p = projectBuilder.build(dependency, true, projectBuildingRequest).getProject();
                CachedProject transpiledDep = loadDependenciesIntoCache(dependency, p, lookupReactorProjects, projectBuilder, projectBuildingRequest, diskCache, pluginVersion, seen, Artifact.SCOPE_COMPILE_PLUS_RUNTIME, excludedDependencies,"  " + depth);
                children.add(transpiledDep);
            }
        }

        // construct an entry in the project, stick it in the map

        CachedProject p;
        if (replace) {
            p = seen.get(key);
            p.replace(artifact, currentProject, children);
        } else {
            p = new CachedProject(diskCache, artifact, currentProject, children);
            seen.put(key, p);

            p.setIgnoreJavacFailure(excludedDependencies.stream().anyMatch(dep -> p.getArtifactKey().startsWith(dep)));

            p.markDirty();
        }

        return p;
    }

    protected static MavenProject getReferencedProject(MavenProject p, Artifact artifact) {
        String key = ArtifactUtils.key(artifact.getGroupId(), artifact.getArtifactId(), artifact.getBaseVersion());
        MavenProject reference = p.getProjectReferences().get(key);
        if (reference != null && reference.getExecutionProject() != null) {
            reference = reference.getExecutionProject();
        }
        return reference;
    }

}
