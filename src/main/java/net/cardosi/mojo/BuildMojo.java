package net.cardosi.mojo;

import java.io.File;
import java.util.List;

import net.cardosi.mojo.builder.SingleCompiler;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.ResolutionScope;

/**
 * Single-time compiler goal
 */
@Mojo(name = "build", requiresDependencyResolution = ResolutionScope.RUNTIME)
public class BuildMojo extends AbstractBuilderRunnerMojo  {

    @Override
    protected void internalExecute(List<File> orderedClasspath) throws MojoExecutionException{
        getLog().info("Start building...");
        try {
            SingleCompiler.run(this, orderedClasspath);
        } catch (Exception e) {
            getLog().error(e);
            throw new MojoExecutionException(e.getMessage());
        }
        getLog().info("...done!");
    }
}
