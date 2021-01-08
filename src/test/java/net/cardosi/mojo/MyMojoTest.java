package net.cardosi.mojo;

//import org.apache.maven.plugin.testing.MojoRule;
//import org.apache.maven.plugin.testing.WithoutMojo;

import java.io.File;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import net.cardosi.mojo.BuildMojo;
import net.cardosi.mojo.WatchMojo;
import org.apache.maven.DefaultMaven;
import org.apache.maven.Maven;
import org.apache.maven.execution.DefaultMavenExecutionRequest;
import org.apache.maven.execution.DefaultMavenExecutionResult;
import org.apache.maven.execution.MavenExecutionRequest;
import org.apache.maven.execution.MavenExecutionRequestPopulator;
import org.apache.maven.execution.MavenExecutionResult;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.graph.GraphBuilder;
import org.apache.maven.lifecycle.internal.LifecycleDependencyResolver;
import org.apache.maven.model.Plugin;
import org.apache.maven.plugin.ContextEnabled;
import org.apache.maven.plugin.Mojo;
import org.apache.maven.plugin.MojoExecution;
import org.apache.maven.plugin.PluginManager;
import org.apache.maven.plugin.descriptor.MojoDescriptor;
import org.apache.maven.plugin.descriptor.PluginDescriptor;
import org.apache.maven.plugin.internal.DefaultPluginManager;
import org.apache.maven.plugin.testing.AbstractMojoTestCase;
import org.apache.maven.plugin.testing.stubs.MavenProjectStub;
import org.apache.maven.project.DefaultProjectBuilder;
import org.apache.maven.project.DependencyResolutionResult;
import org.apache.maven.project.MavenProject;
import org.apache.maven.project.ProjectBuilder;
import org.apache.maven.project.ProjectBuildingRequest;
import org.apache.maven.project.ProjectBuildingResult;
import org.eclipse.aether.DefaultRepositorySystemSession;
import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.internal.impl.SimpleLocalRepositoryManagerFactory;
import org.eclipse.aether.repository.LocalRepository;
import org.eclipse.core.internal.resources.Project;
import org.junit.Rule;
import static org.junit.Assert.*;
import org.junit.Test;

public class MyMojoTest
        extends AbstractMojoTestCase {

    protected void setUp() throws Exception {
        // required for mojo lookups to work
        super.setUp();
    }

    public static void main(String[] args) throws Exception {
        MyMojoTest test = new MyMojoTest();
        test.setUp();
        test.testMojoGoal();
    }

    protected MavenSession newMavenSession() {
        try {
            MavenExecutionRequest request = new DefaultMavenExecutionRequest();
            MavenExecutionResult result = new DefaultMavenExecutionResult();

            // populate sensible defaults, including repository basedir and remote repos
            MavenExecutionRequestPopulator populator;
            populator = getContainer().lookup( MavenExecutionRequestPopulator.class );
            populator.populateDefaults( request );

            // this is needed to allow java profiles to get resolved; i.e. avoid during project builds:
            // [ERROR] Failed to determine Java version for profile java-1.5-detected @ org.apache.commons:commons-parent:22, /Users/alex/.m2/repository/org/apache/commons/commons-parent/22/commons-parent-22.pom, line 909, column 14
            request.setSystemProperties( System.getProperties() );

            // and this is needed so that the repo session in the maven session
            // has a repo manager, and it points at the local repo
            // (cf MavenRepositorySystemUtils.newSession() which is what is otherwise done)
            DefaultMaven maven = (DefaultMaven) getContainer().lookup(Maven.class);
            DefaultRepositorySystemSession repoSession =
                    (DefaultRepositorySystemSession) maven.newRepositorySession( request );
            repoSession.setLocalRepositoryManager(
                    new SimpleLocalRepositoryManagerFactory().newInstance(repoSession,
                                                                          new LocalRepository(request.getLocalRepository().getBasedir() )));

            @SuppressWarnings("deprecation")
            MavenSession session = new MavenSession( getContainer(),
                                                     repoSession,
                                                     request, result );
            return session;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /** As {@link #lookupConfiguredMojo(MavenProject, String)} but taking the pom file
     * and creating the {@link MavenProject}. */
    protected Mojo lookupConfiguredMojo(File pom, String goal) throws Exception {
        assertNotNull( pom );
        assertTrue( pom.exists() );

        MavenSession session = newMavenSession();
        session.getRequest().setPom(pom);
        session.getProjectBuildingRequest().setResolveDependencies(true);

        GraphBuilder gbuilder = getContainer().lookup(GraphBuilder.class);
        gbuilder.build(session);

        List<MavenProject> mavenProjects = session.getProjects();

        ProjectBuilder projectBuilder = lookup(ProjectBuilder.class);
        Method method = projectBuilder.getClass().getDeclaredMethod("resolveDependencies", MavenProject.class, RepositorySystemSession.class);
        method.setAccessible(true);

        for (MavenProject mavenProject : mavenProjects) {
            method.invoke(projectBuilder, mavenProject, session.getRepositorySession());
        }

        final MojoExecution mojoExecution = newMojoExecution(goal);

        MojoDescriptor mojoDescriptor = mojoExecution.getMojoDescriptor();
        PluginDescriptor pluginDescriptor = mojoDescriptor.getPluginDescriptor();

        //session.getProjectBuildingRequest().get
        Plugin plugin = session.getCurrentProject().getBuild().getPluginsAsMap().get(pluginDescriptor.getGroupId() + ":" + pluginDescriptor.getArtifactId());
        pluginDescriptor.setPlugin(plugin);

        AbstractBuildMojo mojo = (AbstractBuildMojo) lookupConfiguredMojo( session, newMojoExecution( goal ) );
        Map<String, Object> pluginContext = mojo.mavenSession.getPluginContext(pluginDescriptor, session.getCurrentProject());
        pluginContext.put( "project", session.getCurrentProject() );
        pluginContext.put( "pluginDescriptor", pluginDescriptor );
        ( (ContextEnabled) mojo ).setPluginContext(pluginContext);

        return mojo;
    }

    @Test
    public void testMojoGoal() throws Exception
    {
        File testPom = new File(getBasedir(),
                                "target/test-classes/hello-world-reactor/pom.xml" );

        Files.exists(testPom.toPath());

        final WatchMojo mojo = (WatchMojo) this.lookupConfiguredMojo(testPom, "watch");
        mojo.execute();
    }


}
