package net.cardosi;

import java.io.File;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import com.google.javascript.jscomp.CompilerOptions;
import com.vertispan.j2cl.Gwt3Options;
import com.vertispan.j2cl.ListeningCompiler;
import org.apache.maven.artifact.DefaultArtifact;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.MavenProject;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.repository.RemoteRepository;

import static net.cardosi.artifactitems.ArtifactItemUtils.copyArtifactFiles;
import static net.cardosi.artifactitems.ArtifactItemUtils.getArtifactFiles;

/**
 * Simple "dev mode" for j2cl+closure, based on the existing bash script. Lots of room for improvement, this
 * isn't intended to be a proposal, just another experiment on the way to one.
 * <p>
 * Assumptions:
 * o The js-compatible JRE is already on the java classpath (need not be on js). Probably not a good one, but
 * on the other hand, we may want to allow changing out the JRE (or skipping it) in favor of something else.
 * o A JS entrypoint already exists. Probably safe, should get some APT going soon as discussed, at least to
 * try it out.
 * <p>
 * Things about this I like:
 * o Treat both jars and jszips as classpaths (ease of dependency system integrations)
 * o Annotation processors are (or should be) run as an IDE would do, so all kinds of changes are picked up. I
 * think I got it right to pick up generated classes changes too...
 * <p>
 * Not so good:
 * o J2CL seems difficult to integrate (no public, uses threadlocals)
 * o Not correctly recompiling classes that require it based on dependencies
 * o Not at all convinced my javac wiring is correct
 * o Polling for changes
 */
@Mojo(name = "build", requiresDependencyResolution = ResolutionScope.RUNTIME)
public class Build extends AbstractJ2CLMojo implements Gwt3Options {

    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    private MavenProject project;

    /**
     * The entry point to Maven Artifact Resolver, i.e. the component doing all the work.
     */
    @Component
    private RepositorySystem repoSystem;

    /**
     * The current repository/network configuration of Maven.
     */
    @Parameter(defaultValue = "${repositorySystemSession}", readonly = true)
    private RepositorySystemSession repoSession;

    /**
     * List of remote repositories
     *
     *
     */
    @Parameter(defaultValue = "${project.remoteProjectRepositories}", readonly = true)
    protected List<RemoteRepository> remoteRepos;

    public void execute() throws MojoExecutionException, MojoFailureException {
        getLog().info("Start building...");
        try {
            createWorkingDirs();
            Map<String, File> artifactFiles = getArtifactFiles(artifactItems, repoSystem, repoSession, remoteRepos);
            copyArtifactFiles(artifactFiles, getWorkingDirs().get(outputDirectory));
            final Set<DefaultArtifact> artifacts = project.getArtifacts();
            final List<String> dependencies = artifacts.stream().map(artifact -> artifact.getFile().getPath()).collect(Collectors.toList());
            bytecodeClasspath.addAll(dependencies);
            getLog().info("bytecodeClasspath " + bytecodeClasspath);
            ListeningCompiler.run(this);
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
    public List<String>  getBytecodeClasspath() {
        return bytecodeClasspath;
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
    public List<String>  getJ2clClasspath() {
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

    protected void createWorkingDirs() throws MojoExecutionException {
        final Map<String, File> workingDirs = getWorkingDirs();
        for (File file : workingDirs.values()) {
            getLog().debug("Creating if not exists: " + file.getPath());
            if (!file.exists() && !file.mkdir()) {
                throw new MojoExecutionException("Failed to create " + file.getPath());
            }
        }
    }
}
