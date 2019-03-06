package net.cardosi.mojo;

import java.io.File;
import java.util.List;

import net.cardosi.mojo.builder.ListeningCompiler;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.ResolutionScope;

/**
 * Incremental, continuous, compiler
 */
@Mojo(name = "run", requiresDependencyResolution = ResolutionScope.RUNTIME)
public class RunMojo extends AbstractBuilderRunnerMojo {

    @Override
    protected void internalExecute(List<File> orderedClasspath) throws MojoExecutionException{
        getLog().info("Start listening...");
        try {
            ListeningCompiler.run(this, orderedClasspath);
        } catch (Exception e) {
            getLog().error(e);
            throw new MojoExecutionException(e.getMessage());
        }
        getLog().info("...done!");
    }
}
