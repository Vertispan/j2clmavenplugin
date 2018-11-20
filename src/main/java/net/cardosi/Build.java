package net.cardosi;

import java.io.File;
import java.util.List;
import java.util.Map;

import com.google.javascript.jscomp.CompilerOptions;
import com.vertispan.j2cl.Gwt3Options;
import com.vertispan.j2cl.ListeningCompiler;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Mojo;

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
@Mojo(name = "build")
public class Build extends AbstractJ2CLMojo implements Gwt3Options {

    public void execute() throws MojoExecutionException, MojoFailureException {
        getLog().info("Start building...");
        try {
            final Map<String, File> workingDirs = getWorkingDirs();
            for (File file : workingDirs.values()) {
                getLog().debug("Creating if not exists: " + file.getPath());
                if (!file.exists() && !file.mkdir()) {
                    throw new MojoExecutionException("Failed to create " + file.getPath());
                }
            }
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
    public String getBytecodeClasspath() {
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
    public String getJ2clClasspath() {
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

}
