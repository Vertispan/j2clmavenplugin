package net.cardosi.mojo;

import net.cardosi.mojo.builder.SingleCompiler;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.ResolutionScope;

/**
 * Single-time compiler goal
 */
@Mojo(name = "build", requiresDependencyResolution = ResolutionScope.RUNTIME)
public class Build extends AbstractBuilderRunnerMojo  {

    @Override
    protected void internalExecute() throws MojoExecutionException{
        getLog().info("Start building...");
        try {
            SingleCompiler.run(this);
        } catch (Exception e) {
            getLog().error(e);
            throw new MojoExecutionException(e.getMessage());
        }
        getLog().info("...done!");
    }
}
