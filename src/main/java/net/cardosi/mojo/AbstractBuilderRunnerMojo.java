package net.cardosi.mojo;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import com.google.javascript.jscomp.CompilerOptions;
import net.cardosi.mojo.options.Gwt3Options;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.apache.maven.shared.dependency.graph.DependencyGraphBuilder;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.repository.RemoteRepository;

import static net.cardosi.mojo.artifactitems.ArtifactItemUtils.copyArtifactFiles;
import static net.cardosi.mojo.artifactitems.ArtifactItemUtils.getArtifactFiles;

/**
 * Abstract class to be extended by BuildMojo/RunMojo mojos
 */
public abstract class AbstractBuilderRunnerMojo extends AbstractJ2CLMojo implements Gwt3Options {

    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    protected MavenProject project;

    @Parameter(defaultValue = "${session}", readonly = true, required = true)
    protected MavenSession session;

    /**
     * Contains the full list of projects in the reactor.
     */
    @Parameter(defaultValue = "${reactorProjects}", readonly = true, required = true)
    protected List<MavenProject> reactorProjects;

    /**
     * The dependency tree builder to use.
     */
    @Component(hint = "default")
    protected DependencyGraphBuilder dependencyGraphBuilder;

    /**
     * The entry point to Maven Artifact Resolver, i.e. the component doing all the work.
     */
    @Component
    protected RepositorySystem repoSystem;

    /**
     * The current repository/network configuration of Maven.
     */
    @Parameter(defaultValue = "${repositorySystemSession}", readonly = true)
    protected RepositorySystemSession repoSession;

    /**
     * List of remote repositories
     */
    @Parameter(defaultValue = "${project.remoteProjectRepositories}", readonly = true)
    protected List<RemoteRepository> remoteRepos;

    public void execute() throws MojoExecutionException, MojoFailureException {
        getLog().info("Start building...");
        try {
            createWorkingDirs();
            Map<String, File> artifactFiles = getArtifactFiles(artifactItems, repoSystem, repoSession, remoteRepos);
            final Map<String, File> workingDirs = getWorkingDirs();
            copyArtifactFiles(artifactFiles, workingDirs.get(outputDirectory));
            final Set<Artifact> artifacts = project.getArtifacts();
            final List<String> dependencies = artifacts.stream().map(artifact -> artifact.getFile().getPath()).collect(Collectors.toList());
            bytecodeClasspath.addAll(dependencies);
            getLog().info("raw bytecodeClasspath " + bytecodeClasspath);
            // We need to remove from bytecodeClasspath the jars of the modules transpiled, so that at each recompilation the updated version is used, and not the original jar
            bytecodeClasspath = cleanClassPath(artifacts, bytecodeClasspath);
            getLog().info("cleaned bytecodeClasspath " + bytecodeClasspath);
            final List<File> orderedClasspath = DependencyBuilder.getOrderedClasspath(session, dependencyGraphBuilder, project, reactorProjects, null);
            getLog().info("orderedClasspath " + orderedClasspath);
            final Map<String, MavenProject> baseDirProjectMap = new HashMap<>();
            reactorProjects.forEach(mavenProject -> mavenProject.getCompileSourceRoots().forEach(sourceRoot -> baseDirProjectMap.put(sourceRoot, mavenProject)));
            project.getCompileSourceRoots().forEach(sourceRoot -> baseDirProjectMap.put(sourceRoot, project));
            internalExecute(orderedClasspath, workingDirs.get(targetPath), baseDirProjectMap);
        } catch (Exception e) {
            getLog().error(e);
            throw new MojoExecutionException(e.getMessage());
        }
    }

    @Override
    public String getIntermediateJsPath() {
        return intermediateJsPath;
    }

    @Override
    public File getClassesDir() {
        return new File(classesDir);
    }

    @Override
    public boolean isDeclareLegacyNamespaces() {
        return declareLegacyNamespaces;
    }

    @Override
    public List<String> getBytecodeClasspath() {
        return bytecodeClasspath;
    }

    @Override
    public File getBootstrapClasspath() {
        return new File(javacBootClasspath);
    }

    @Override
    public String getJsOutputFile() {
        return outputJsPathDir + "/app.js";
    }

    @Override
    public List<String> getEntrypoint() {
        return entrypoint;
    }

    @Override
    public List<String> getDefine() {
        return define;
    }

    @Override
    public List<String> getExterns() {
        return externs;
    }

    @Override
    public String getLanguageOut() {
        return languageOut;
    }

    @Override
    public String getCompilationLevel() {
        return compilationLevel;
    }

    @Override
    public CompilerOptions.DependencyMode getDependencyMode() {
        return dependencyMode;
    }

    @Override
    public List<String> getJ2clClasspath() {
        return j2clClasspath;
    }

    @Override
    public List<String> getSourceDir() {
        return sourceDir;
    }

    @Override
    public String getJsZipCacheDir() {
        return jsZipCacheDir;
    }

    @Override
    public String getOutputJsPathDir() {
        return outputJsPathDir;
    }

    protected abstract void internalExecute(List<File> orderedClasspath, File targetPath, Map<String, MavenProject> baseDirProjectMap) throws MojoExecutionException;

    protected void createWorkingDirs() throws MojoExecutionException {
        final Map<String, File> workingDirs = getWorkingDirs();
        for (File file : workingDirs.values()) {
            getLog().debug("Creating if not exists: " + file.getPath());
            if (!file.exists() && !file.mkdir()) {
                throw new MojoExecutionException("Failed to create " + file.getPath());
            }
        }
    }

    /**
     * Remove from given <code>List&lt;String&gt;</code> all the files included in given <code>Set&lt;Artifact&gt;</code>
     * @param artifacts
     * @param toClean
     * @return
     */
    private List<String> cleanClassPath(Set<Artifact> artifacts, List<String> toClean) {
        List<Artifact> toRemove = excludedArtifacts.stream()
                .map(excludedArtifact -> artifacts
                        .stream()
                        .filter(artifact -> artifact.getArtifactId().equals(excludedArtifact.getArtifactId())
                                && artifact.getGroupId().equals(excludedArtifact.getGroupId())
                                && artifact.getVersion().equals(excludedArtifact.getVersion()))
                        .findFirst().orElse(null))
                .collect(Collectors.toList());
        final List<String> toReturn = new ArrayList<>();
        toRemove.forEach(artifact -> {
            final String absolutePath = artifact.getFile().getAbsolutePath();
            toReturn.addAll(toClean.stream()
                                    .map(s -> {
                                        String cleanedString = s.replace(absolutePath, "").replace("::", ":");
                                        if (cleanedString.startsWith(":")) {
                                            cleanedString = cleanedString.substring(1);
                                        }
                                        return cleanedString;
                                    })
                                    .collect(Collectors.toList()));
        });
        return toReturn;
    }
}
