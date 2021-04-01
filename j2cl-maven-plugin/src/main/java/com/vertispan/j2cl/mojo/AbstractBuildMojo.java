package com.vertispan.j2cl.mojo;

import net.cardosi.mojo.Versions;
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
