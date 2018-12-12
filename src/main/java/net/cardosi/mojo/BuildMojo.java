package net.cardosi.mojo;

import net.cardosi.mojo.builder.SingleCompiler;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.shared.dependency.graph.DependencyNode;

/**
 * Single-time compiler goal
 */
@Mojo(name = "build", requiresDependencyResolution = ResolutionScope.RUNTIME)
public class BuildMojo extends AbstractBuilderRunnerMojo  {

    @Override
    protected void internalExecute() throws MojoExecutionException{
        getLog().info("Start building...");
        try {
            final DependencyNode dependencyNode = DependencyBuilder.getDependencyNode(session, dependencyGraphBuilder, project, reactorProjects, null);
            SingleCompiler.run(this);
        } catch (Exception e) {
            getLog().error(e);
            throw new MojoExecutionException(e.getMessage());
        }
        getLog().info("...done!");
    }
}
