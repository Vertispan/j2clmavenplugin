package net.cardosi;

import com.vertispan.j2cl.ListeningCompiler;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.ResolutionScope;

/**
 * Incremental, continuous, compiler
 */
@Mojo(name = "run", requiresDependencyResolution = ResolutionScope.RUNTIME)
public class Run extends AbstractBuilderRunnerMojo {

    @Override
    protected void internalExecute() throws MojoExecutionException{
        getLog().info("Start listening...");
        try {
            ListeningCompiler.run(this);
        } catch (Exception e) {
            getLog().error(e);
            throw new MojoExecutionException(e.getMessage());
        }
        getLog().info("...done!");
    }
}
