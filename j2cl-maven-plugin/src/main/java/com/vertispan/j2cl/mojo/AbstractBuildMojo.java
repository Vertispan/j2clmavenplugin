package com.vertispan.j2cl.mojo;

import com.vertispan.j2cl.build.Dependency;
import com.vertispan.j2cl.build.Project;
import com.vertispan.j2cl.build.task.OutputTypes;
import net.cardosi.mojo.Versions;
import org.apache.maven.RepositoryUtils;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.ArtifactUtils;
import org.apache.maven.artifact.resolver.filter.ScopeArtifactFilter;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.FileSet;
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
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public abstract class AbstractBuildMojo extends AbstractCacheMojo {
    public static final String BUNDLE_JAR = "BUNDLE_JAR";

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

    @Parameter
    protected String workerThreadCount = "4";

    // tools to resolve dependencies
    @Component
    protected RepositorySystem repoSystem;

    @Parameter( defaultValue = "${repositorySystemSession}", readonly = true, required = true )
    protected RepositorySystemSession repoSession;

    @Parameter( defaultValue = "${project.remoteProjectRepositories}", readonly = true, required = true )
    protected List<RemoteRepository> repositories;

    private static String key(Artifact artifact) {
        // this is roughly DefaultArtifact.toString, minus scope, since we don't care what the scope is for the purposes of building projects
        String key = artifact.getGroupId() + ":" + artifact.getArtifactId() + ":" + artifact.getBaseVersion();
        if (artifact.getClassifier() != null) {
            key += ":" + artifact.getClassifier();
        }
        return key;
    }

    protected int getWorkerTheadCount() {
        // Use the same algorithm as org.apache.maven.cli.MavenCli
        if (workerThreadCount.contains("C")) {
            return (int) (Float.parseFloat(workerThreadCount.replace("C", "")) * Runtime.getRuntime().availableProcessors());
        }
        return Integer.parseInt(workerThreadCount);
    }

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

    protected static String getOutputTask(String compilationLevel) {
        if (compilationLevel.equalsIgnoreCase(BUNDLE_JAR)) {
            return OutputTypes.BUNDLED_JS_APP;
        }
        return OutputTypes.OPTIMIZED_JS;
    }

    /**
     * Helper to recursively construct projects that we can build from the already-built maven details.
     *
     * @param mavenProject
     * @param artifact
     * @param lookupReactorProjects
     * @param projectBuilder
     * @param request
     * @param pluginVersion
     * @param builtProjects
     * @param dependencyReplacements
     * @return
     */
    protected Project buildProject(MavenProject mavenProject, Artifact artifact, boolean lookupReactorProjects, ProjectBuilder projectBuilder, ProjectBuildingRequest request, String pluginVersion, LinkedHashMap<String, Project> builtProjects, String classpathScope, List<DependencyReplacement> dependencyReplacements) throws ProjectBuildingException {

        String key = AbstractBuildMojo.key(artifact);
        Project project = new Project(key);

        List<Dependency> dependencies = new ArrayList<>();

        // convert to list before iterating, we will sometimes append extra things we discover along the way
        List<Artifact> mavenDeps = new ArrayList<>(mavenProject.getArtifacts());
        for (int i = 0; i < mavenDeps.size(); i++) {
            Artifact mavenDependency = mavenDeps.get(i);
            // if it shouldnt be on the classpath, skip it, if it isn't part of the current expected scope, skip it

            if (!mavenDependency.getArtifactHandler().isAddedToClasspath()) {
                continue;
            } else if (Artifact.SCOPE_SYSTEM.equalsIgnoreCase(mavenDependency.getScope())) {
                continue;
            } else if (!new ScopeArtifactFilter(classpathScope).include(mavenDependency)) {
                continue;
            }

            // we're still interested in it, but it might be something the build has been configured to replace
            Optional<DependencyReplacement> replacement = getReplacement(dependencyReplacements, mavenDependency);
            boolean appendDependencies = false;
            if (replacement.isPresent()) {
                Artifact old = mavenDependency;
                mavenDependency = replacement.get().getReplacementArtifact(mavenDependency);
                if (mavenDependency == null) {
                    System.out.println("Removing dependency " + old + ", no replacement");
                    continue;
                }
                System.out.println("Removing dependency " + old + ", replacing with " + mavenDependency);
                appendDependencies = true;
            }

            String depKey = AbstractBuildMojo.key(mavenDependency);

            final Project child;
            if (builtProjects.containsKey(depKey)) {
                child = builtProjects.get(depKey);
            } else {
                MavenProject p = lookupReactorProjects ? getReferencedProject(mavenProject, mavenDependency) : null;
                if (p != null) {
                    child = buildProject(p, mavenDependency, lookupReactorProjects, projectBuilder, request, pluginVersion, builtProjects, Artifact.SCOPE_COMPILE_PLUS_RUNTIME, dependencyReplacements);
                } else {
                    // non-reactor project (or we don't want it to be from reactor), build a project for it
                    request.setProject(null);
                    request.setResolveDependencies(true);
                    request.setRemoteRepositories(null);
                    p = projectBuilder.build(mavenDependency, true, request).getProject();

                    // at this point, we know that the dependency is not in the reactor, but may not have the artifact, so
                    // resolve it.
                    try {
                        repoSystem.resolveArtifact(repoSession, new ArtifactRequest().setRepositories(repositories).setArtifact(RepositoryUtils.toArtifact(mavenDependency)));
                    } catch (ArtifactResolutionException e) {
                        throw new ProjectBuildingException(p.getId(), "Failed to resolve this project's artifact file", e);
                    }

                    child = buildProject(p, mavenDependency, lookupReactorProjects, projectBuilder, request, pluginVersion, builtProjects, Artifact.SCOPE_COMPILE_PLUS_RUNTIME, dependencyReplacements);
                }

                if (appendDependencies) {
                    mavenDeps.addAll(p.getArtifacts());
                }
            }

            // construct a dependency node for this, and attach it to the new project
            Dependency dep = new Dependency();
            dep.setProject(child);
            dep.setScope(translateScope(mavenDependency.getScope()));
            dependencies.add(dep);
        }
        project.setDependencies(dependencies);

        if (mavenProject.getCompileSourceRoots().isEmpty() && mavenProject.getResources().isEmpty()) {
            project.setSourceRoots(Collections.singletonList(artifact.getFile().toString()));
        } else {
            project.setSourceRoots(
                    Stream.concat(
                            mavenProject.getCompileSourceRoots().stream(),
                            mavenProject.getResources().stream().map(FileSet::getDirectory)
                    ).collect(Collectors.toList())
            );
        }

        builtProjects.put(key, project);

        return project;
    }

    private Dependency.Scope translateScope(String scope) {
        if (scope == null) {
            return Dependency.Scope.BOTH;
        }
        switch (scope) {
            case Artifact.SCOPE_COMPILE:
            case Artifact.SCOPE_TEST:
                return Dependency.Scope.BOTH;
            case Artifact.SCOPE_PROVIDED:
                return Dependency.Scope.COMPILE;
            case Artifact.SCOPE_RUNTIME:
                return Dependency.Scope.RUNTIME;
            // These two should already be removed by earlier filtering, throw anyway
            case Artifact.SCOPE_IMPORT:
            case Artifact.SCOPE_SYSTEM:
            default:
                throw new IllegalStateException("Unsupported scope: " + scope);
        }
    }
}
