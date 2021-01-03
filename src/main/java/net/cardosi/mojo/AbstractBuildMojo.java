package net.cardosi.mojo;

import net.cardosi.mojo.cache.CachedProject;
import net.cardosi.mojo.cache.DiskCache;
import net.cardosi.mojo.config.DependencyReplacement;
import org.apache.maven.RepositoryUtils;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.ArtifactUtils;
import org.apache.maven.artifact.resolver.filter.ScopeArtifactFilter;
import org.apache.maven.execution.MavenSession;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

public abstract class AbstractBuildMojo extends AbstractCacheMojo {
    @Parameter( defaultValue = "${session}", readonly = true)
    protected MavenSession mavenSession;

    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    protected MavenProject project;

    @Component
    protected ProjectBuilder projectBuilder;

    @Parameter(defaultValue = "com.vertispan.j2cl:javac-bootstrap-classpath:" + Versions.J2CL_VERSION, required = true)
    protected String javacBootstrapClasspathJar;

    @Parameter(defaultValue = "com.vertispan.j2cl:jre:" + Versions.J2CL_VERSION, required = true)
    protected String jreJar;
    @Parameter(defaultValue = "com.vertispan.j2cl:jre:zip:jszip:" + Versions.J2CL_VERSION, required = true)
    protected String jreJsZip;

    @Parameter(defaultValue = "com.vertispan.j2cl:bootstrap:zip:jszip:" + Versions.J2CL_VERSION, required = true)
    protected String bootstrapJsZip;

    @Parameter(defaultValue = "com.vertispan.j2cl:closure-test:zip:jszip:" + Versions.J2CL_VERSION, required = true)
    protected String testJsZip;

    @Parameter(defaultValue = "com.vertispan.j2cl:gwt-internal-annotations:" + Versions.J2CL_VERSION, required = true)
    protected String internalAnnotationsJar;

    @Parameter(defaultValue = "com.google.jsinterop:jsinterop-annotations:2.0.0", required = true)
    protected String jsinteropAnnotationsJar;

    @Parameter(defaultValue = "com.vertispan.j2cl:junit-annotations:" + Versions.J2CL_VERSION, required = true)
    protected String junitAnnotations;

    // optional, if not specified, we'll use the defaults
    @Parameter
    protected List<DependencyReplacement> dependencyReplacements;

    private List<DependencyReplacement> defaultDependencyReplacements = Arrays.asList(
            new DependencyReplacement("com.google.jsinterop:base", "com.vertispan.jsinterop:base:" + Versions.VERTISPAN_JSINTEROP_BASE_VERSION),
            new DependencyReplacement("org.realityforge.com.google.jsinterop:base", "com.vertispan.jsinterop:base:" + Versions.VERTISPAN_JSINTEROP_BASE_VERSION),
            new DependencyReplacement("com.google.gwt:gwt-user", null),
            new DependencyReplacement("com.google.gwt:gwt-dev", null),
            new DependencyReplacement("com.google.gwt:gwt-servlet", null)
    );

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

    protected List<DependencyReplacement> getDependencyReplacements() {
        if (dependencyReplacements != null) {
            dependencyReplacements.forEach(dependencyReplacement -> dependencyReplacement.resolve(repoSession, repositories, repoSystem));
            return dependencyReplacements;
        }
        defaultDependencyReplacements.forEach(dependencyReplacement -> dependencyReplacement.resolve(repoSession, repositories, repoSystem));
        return defaultDependencyReplacements;
    }

    protected CachedProject loadDependenciesIntoCache(
            Artifact artifact,
            MavenProject currentProject,
            boolean lookupReactorProjects,
            ProjectBuilder projectBuilder,
            ProjectBuildingRequest projectBuildingRequest,
            DiskCache diskCache,
            String pluginVersion,
            Map<String, CachedProject> seen,
            String classpathScope,
            List<DependencyReplacement> replacedDependencies,
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
        List<Artifact> dependencies = currentProject.getArtifacts().stream().sorted().collect(Collectors.toCollection(ArrayList::new));
        for (int i = 0; i < dependencies.size(); i++) {
            Artifact dependency = dependencies.get(i);
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

            // we're still interested in it, but it might be something the build has been configured to replace
            Optional<DependencyReplacement> replacement = getReplacement(replacedDependencies, dependency);
            boolean appendDependencies = false;
            if (replacement.isPresent()) {
                Artifact old = dependency;
                dependency = replacement.get().getReplacementArtifact(dependency);
                if (dependency == null) {
                    System.out.println("Removing dependency " + old + ", no replacement");
                    continue;
                }
                System.out.println("Removing dependency " + old + ", replacing with " + dependency);
                appendDependencies = true;
            }
//            System.out.println("\t" + artifact + " depends on " + dependency);

            // make a project to reference for this dependency, if possible
            //TODO handle the case where a jar is referenced without a pom by treating it as having no dependencies

            MavenProject p = lookupReactorProjects ? getReferencedProject(currentProject, dependency) : null;
            if (p != null) {
//                System.out.println("Found project in reactor matching this " + p);

//                Artifact replacementArtifact = new org.apache.maven.artifact.DefaultArtifact(
//                        dependency.getGroupId(),
//                        dependency.getArtifactId(),
//                        dependency.getVersion(),
//                        dependency.getScope(),
//                        dependency.getType(),
//                        null,
//                        dependency.getArtifactHandler()
//                );
                CachedProject transpiledDep = loadDependenciesIntoCache(dependency, p, lookupReactorProjects, projectBuilder, projectBuildingRequest, diskCache, pluginVersion, seen, Artifact.SCOPE_COMPILE_PLUS_RUNTIME, replacedDependencies, "  " + depth);
                children.add(transpiledDep);
            } else {
                // non-reactor project, build a project for it
//                System.out.println("Creating project from artifact " + dependency);
                projectBuildingRequest.setProject(null);
                projectBuildingRequest.setResolveDependencies(true);
                projectBuildingRequest.setRemoteRepositories(null);
                p = projectBuilder.build(dependency, true, projectBuildingRequest).getProject();

                // at this point, we know that the dependency is not in the reactor, but may not have the artifact, so
                // resolve it.
                try {
                    repoSystem.resolveArtifact(repoSession, new ArtifactRequest().setRepositories(repositories).setArtifact(RepositoryUtils.toArtifact(dependency)));
                } catch (ArtifactResolutionException e) {
                    throw new ProjectBuildingException(p.getId(), "Failed to resolve this project's artifact file", e);
                }
                CachedProject transpiledDep = loadDependenciesIntoCache(dependency, p, lookupReactorProjects, projectBuilder, projectBuildingRequest, diskCache, pluginVersion, seen, Artifact.SCOPE_COMPILE_PLUS_RUNTIME, replacedDependencies, "  " + depth);
                children.add(transpiledDep);
            }
            if (appendDependencies) {
                dependencies.addAll(p.getArtifacts());
            }
        }

        // construct an entry in the project, stick it in the map

        CachedProject p;
        if (replace) {
            p = seen.get(key);
            p.replace(artifact, currentProject, children);
        } else {
            Path webappPath = null;
            File basedir = currentProject.getBasedir();
            if (basedir != null) {
                webappPath = basedir.toPath().resolve("src/main/webapp");
                if (!Files.exists(webappPath)) webappPath = null;
            }

            p = new CachedProject(diskCache, artifact, currentProject, children, webappPath);
            seen.put(key, p);
            p.markDirty();
        }

        return p;
    }

    private static Optional<DependencyReplacement> getReplacement(List<DependencyReplacement> replacedDependencies, Artifact dependency) {
        return replacedDependencies.stream().filter(r -> r.matches(dependency)).findFirst();
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
