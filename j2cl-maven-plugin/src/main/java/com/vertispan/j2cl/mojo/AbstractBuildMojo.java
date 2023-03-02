package com.vertispan.j2cl.mojo;

import com.vertispan.j2cl.build.Dependency;
import com.vertispan.j2cl.build.DiskCache;
import com.vertispan.j2cl.build.Project;
import com.vertispan.j2cl.build.TaskRegistry;
import com.vertispan.j2cl.build.provided.SkipAptTask;
import com.vertispan.j2cl.build.task.OutputTypes;
import org.apache.commons.lang3.StringUtils;
import org.apache.maven.RepositoryUtils;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.ArtifactUtils;
import org.apache.maven.artifact.resolver.filter.ScopeArtifactFilter;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.FileSet;
import org.apache.maven.plugin.MojoExecution;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.apache.maven.project.ProjectBuilder;
import org.apache.maven.project.ProjectBuildingException;
import org.apache.maven.project.ProjectBuildingRequest;
import org.codehaus.plexus.util.xml.Xpp3Dom;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.aether.resolution.ArtifactRequest;
import org.eclipse.aether.resolution.ArtifactResolutionException;
import org.eclipse.aether.resolution.ArtifactResult;

import javax.annotation.Nullable;
import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public abstract class AbstractBuildMojo extends AbstractCacheMojo {
    public static final String BUNDLE_JAR = "BUNDLE_JAR";

    @Parameter( defaultValue = "${session}", readonly = true)
    protected MavenSession mavenSession;

    @Parameter(defaultValue = "${reactorProjects}", required = true, readonly = true)
    protected List<MavenProject> reactorProjects;

    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    protected MavenProject project;

    @Component
    protected ProjectBuilder projectBuilder;

    @Parameter(defaultValue = "com.vertispan.j2cl:javac-bootstrap-classpath:" + Versions.J2CL_VERSION, required = true, alias = "javacBootstrapClasspathJar")
    protected String bootstrapClasspath;

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

    @Parameter(defaultValue = "AVOID_MAVEN")
    private AnnotationProcessorMode annotationProcessorMode;

    @Parameter(defaultValue = "false", property = "j2cl.incremental")
    private boolean incrementalEnabled;

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

    @Parameter(readonly = true, defaultValue = "${mojoExecution}")
    protected MojoExecution mojoExecution;

    @Parameter
    protected Map<String, String> taskMappings = new HashMap<>();

    @Parameter
    private int shutdownWaitSeconds = 10;

    private static String key(Artifact artifact) {
        // this is roughly DefaultArtifact.toString, minus scope, since we don't care what the scope is for the purposes of building projects
        String key = artifact.getGroupId() + ":" + artifact.getArtifactId() + ":" + artifact.getBaseVersion();
        if (artifact.getClassifier() != null) {
            key += ":" + artifact.getClassifier();
        }
        return key;
    }

    protected static Xpp3Dom merge(Xpp3Dom pluginConfiguration, Xpp3Dom configuration) {
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

    protected int getWorkerTheadCount() {
        // Use the same algorithm as org.apache.maven.cli.MavenCli
        if (workerThreadCount.contains("C")) {
            return (int) (Float.parseFloat(workerThreadCount.replace("C", "")) * Runtime.getRuntime().availableProcessors());
        }
        return Integer.parseInt(workerThreadCount);
    }

    protected Artifact getMavenArtifactWithCoords(String coords) throws MojoExecutionException {
        ArtifactRequest request = new ArtifactRequest()
                .setRepositories(repositories)
                .setArtifact(new DefaultArtifact(coords));

        try {
            ArtifactResult result = repoSystem.resolveArtifact(repoSession, request);
            return RepositoryUtils.toArtifact(result.getArtifact());
        } catch (ArtifactResolutionException e) {
            throw new MojoExecutionException("Failed to find artifact " + coords, e);
        }
    }

    protected File getFileWithMavenCoords(String coords) throws MojoExecutionException {
        ArtifactRequest request = new ArtifactRequest()
                .setRepositories(repositories)
                .setArtifact(new DefaultArtifact(coords));

        try {
            return repoSystem.resolveArtifact(repoSession, request).getArtifact().getFile();
        } catch (ArtifactResolutionException e) {
            throw new MojoExecutionException("Failed to find artifact " + coords, e);
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

    @Nullable
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
    protected Project buildProject(MavenProject mavenProject, Artifact artifact, boolean lookupReactorProjects, ProjectBuilder projectBuilder, ProjectBuildingRequest request, String pluginVersion, LinkedHashMap<String, Project> builtProjects, String classpathScope, List<DependencyReplacement> dependencyReplacements, List<Artifact> extraJsZips) throws ProjectBuildingException {
        Project finalProject = buildProjectHelper(mavenProject, artifact, lookupReactorProjects, projectBuilder, request, pluginVersion, builtProjects, classpathScope, dependencyReplacements, 0);

        // Attach any jszip dependencies as runtime-only dependencies of the final project
        // Note that this assumes/requires that this is not covered by dependency-replacements
        List<Project> seenJsZipProjects = new ArrayList<>();
        for (Artifact extraJsZip : extraJsZips) {
            String key = key(extraJsZip);
            final Project child;
            if (builtProjects.containsKey(key)) {
                child = builtProjects.get(key);
            } else {
                MavenProject p = lookupReactorProjects ? getReferencedProject(mavenProject, extraJsZip) : null;
                if (p == null) {
                    p = resolveNonReactorProjectForArtifact(projectBuilder, request, extraJsZip);
                }
                child = buildProjectHelper(p, extraJsZip, lookupReactorProjects, projectBuilder, request, pluginVersion, builtProjects, Artifact.SCOPE_COMPILE_PLUS_RUNTIME, dependencyReplacements, 1);
                child.getDependencies().clear();//ignore default jar deps
            }
            child.markJsZip();
            Stream.concat(seenJsZipProjects.stream(), builtProjects.values().stream().filter(proj -> extraJsZips.stream().noneMatch(a -> key(a).equals(proj.getKey())))).forEach(proj -> {
                Dependency dependency = new Dependency();
                dependency.setScope(com.vertispan.j2cl.build.task.Dependency.Scope.BOTH);
                dependency.setProject(child);
                ArrayList<com.vertispan.j2cl.build.task.Dependency> deps = new ArrayList<>(proj.getDependencies());
                deps.add(dependency);
                proj.setDependencies(deps);
            });
            seenJsZipProjects.add(child);

        }

        // Before returning this, log the full dependency tree if requested. We do this afterwards instead of
        // during, so that other logs don't make it hard to read
        if (getLog().isDebugEnabled()) {
            getLog().debug(StringUtils.repeat('=', 72));

            writeProjectAndDeps(finalProject, 0, new HashSet<>());

            getLog().debug(StringUtils.repeat('=', 72));
        }

        return finalProject;
    }

    private void writeProjectAndDeps(com.vertispan.j2cl.build.task.Project project, int depth, Set<String> seenKeys) {
        String prefix = StringUtils.repeat(' ', 2 * depth);
        // always log this item
        getLog().debug(prefix + "* " + project.getKey());
        // only visit children if we haven't seen this key before
        if (seenKeys.add(project.getKey())) {
            for (com.vertispan.j2cl.build.task.Dependency dep : project.getDependencies()) {
                writeProjectAndDeps(dep.getProject(), depth + 1, seenKeys);
            }
        }
    }

    private Project buildProjectHelper(MavenProject mavenProject, Artifact artifact, boolean lookupReactorProjects, ProjectBuilder projectBuilder, ProjectBuildingRequest request, String pluginVersion, LinkedHashMap<String, Project> builtProjects, String classpathScope, List<DependencyReplacement> dependencyReplacements, int depth) throws ProjectBuildingException {
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
                    getLog().info("Removing dependency " + old + ", no replacement");
                    continue;
                }
                getLog().info("Removing dependency " + old + ", replacing with " + mavenDependency);
                appendDependencies = true;
            }

            String depKey = AbstractBuildMojo.key(mavenDependency);

            final Project child;
            if (builtProjects.containsKey(depKey)) {
                child = builtProjects.get(depKey);
            } else {
                MavenProject p = lookupReactorProjects ? getReferencedProject(mavenProject, mavenDependency) : null;
                if (p == null) {
                    // non-reactor project (or we don't want it to be from reactor), build a project for it
                    p = resolveNonReactorProjectForArtifact(projectBuilder, request, mavenDependency);
                }
                child = buildProjectHelper(p, mavenDependency, lookupReactorProjects, projectBuilder, request, pluginVersion, builtProjects, Artifact.SCOPE_COMPILE_PLUS_RUNTIME, dependencyReplacements, depth++);

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

        // we only check for sources, not resources, as resources are always populated even for non-reactor projects
        boolean hasSourcesMapped = reactorProjects.contains(mavenProject);

        if (hasSourcesMapped) {
            //TODO support local checkouts of artifacts to map sources to
            project.setSourceRoots(
                    Stream.concat(
                            mavenProject.getCompileSourceRoots().stream(),
                            mavenProject.getResources().stream().map(FileSet::getDirectory)
                    )
                            .distinct()
                            .filter(withSourceRootFilter())
                            .collect(Collectors.toUnmodifiableList())
            );
        } else {
            project.setSourceRoots(Collections.singletonList(artifact.getFile().toString()));
        }

        builtProjects.put(key, project);

        return project;
    }

    private MavenProject resolveNonReactorProjectForArtifact(ProjectBuilder projectBuilder, ProjectBuildingRequest request, Artifact mavenDependency) throws ProjectBuildingException {
        MavenProject p;
        request.setProject(null);
        request.setResolveDependencies(true);
        request.setRemoteRepositories(null);

        // A type will confuse maven here, since it will incorrectly treat it as packaging
        Artifact deTypedDependency = new org.apache.maven.artifact.DefaultArtifact(mavenDependency.getGroupId(), mavenDependency.getArtifactId(), mavenDependency.getVersionRange(), mavenDependency.getScope(), "jar", mavenDependency.getClassifier(), mavenDependency.getArtifactHandler());
        p = projectBuilder.build(deTypedDependency, true, request).getProject();

        // at this point, we know that the dependency is not in the reactor, but may not have the artifact, so
        // resolve it.
        try {
            repoSystem.resolveArtifact(repoSession, new ArtifactRequest().setRepositories(repositories).setArtifact(RepositoryUtils.toArtifact(mavenDependency)));
        } catch (ArtifactResolutionException e) {
            throw new ProjectBuildingException(p.getId(), "Failed to resolve this project's artifact file", e);
        }
        return p;
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
                return Dependency.Scope.BOTH;
            // These two should already be removed by earlier filtering, throw anyway
            case Artifact.SCOPE_IMPORT:
            case Artifact.SCOPE_SYSTEM:
            default:
                throw new IllegalStateException("Unsupported scope: " + scope);
        }
    }

    protected TaskRegistry createTaskRegistry() {
        if (!annotationProcessorMode.pluginShouldRunApt()) {
            taskMappings.put(OutputTypes.BYTECODE, SkipAptTask.SKIP_TASK_NAME);
        }
        // use any task wiring if specified
        return new TaskRegistry(taskMappings);
    }

    protected Predicate<String> withSourceRootFilter() {
        return path -> new File(path).exists() &&
            !(annotationProcessorMode.pluginShouldExcludeGeneratedAnnotationsDir()
                && (path.endsWith("generated-test-sources" + File.separator + "test-annotations") || 
                    path.endsWith("generated-sources" + File.separator + "annotations")));
    }

    protected void addShutdownHook(ScheduledExecutorService executor, DiskCache diskCache) {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                // first prevent new tasks from starting
                executor.shutdown();

                // next, make sure the disk cache doesn't try to pick up work - this will block on joining
                // to the watching thread, so we ran shutdown above
                diskCache.close();

                // finally, interrupt running work and wait a short time for that to stop
                executor.shutdownNow();
                executor.awaitTermination(shutdownWaitSeconds, TimeUnit.SECONDS);

            } catch (IOException e) {
                executor.shutdownNow();
                e.printStackTrace();
            } catch (InterruptedException e) {
                executor.shutdownNow();
                e.printStackTrace();
                Thread.currentThread().interrupt();
            }
        }));
    }
}
