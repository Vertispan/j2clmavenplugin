package net.cardosi.mojo.cache;

//import org.apache.maven.plugin.testing.MojoRule;
//import org.apache.maven.plugin.testing.WithoutMojo;

import java.io.File;
import java.nio.file.Files;

import net.cardosi.mojo.BuildMojo;
import net.cardosi.mojo.WatchMojo;
import org.apache.maven.execution.DefaultMavenExecutionRequest;
import org.apache.maven.execution.MavenExecutionRequest;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.MojoExecution;
import org.apache.maven.plugin.testing.AbstractMojoTestCase;
import org.apache.maven.plugin.testing.stubs.MavenProjectStub;
import org.apache.maven.project.MavenProject;
import org.apache.maven.project.ProjectBuilder;
import org.apache.maven.project.ProjectBuildingRequest;
import org.junit.Rule;
import static org.junit.Assert.*;
import org.junit.Test;

public class MyMojoTest
        extends AbstractMojoTestCase
{

    protected void setUp() throws Exception
    {
        // required for mojo lookups to work
        super.setUp();
    }

    @Test
    public void testMojoGoal() throws Exception
    {
        MavenProject       project = new MavenProjectStub();
        final MavenSession session = newMavenSession(project);
        session.getRequest().setUserProperties(project.getModel().getProperties());
        final MojoExecution execution = newMojoExecution("build");
        final BuildMojo     mojo      = (BuildMojo) this.lookupConfiguredMojo(session, execution);
        //mojo.setB = "target";


        session.getRequest().setUserProperties(project.getModel().getProperties());

        File testPom = new File(getBasedir(),
                                "target/test-classes/hello-world-reactor/pom.xml" );

        Files.exists(testPom.toPath());



        MavenExecutionRequest  executionRequest = new DefaultMavenExecutionRequest();
        ProjectBuildingRequest buildingRequest = executionRequest.getProjectBuildingRequest();
        ProjectBuilder projectBuilder = this.lookup(ProjectBuilder.class);
        MavenProject   project        = projectBuilder.build(testPom, buildingRequest).getProject();

        BuildMojo mojo = (BuildMojo) this.lookupConfiguredMojo(project, "build");

//        BuildMojo mojo = (BuildMojo) lookupMojo("build", testPom);
//
//        lookupConfiguredMojo()
//
//        assertNotNull( mojo );
//
//        mojo.execute();


    }
//    @Rule
//    public MojoRule rule = new MojoRule()
//    {
//        @Override
//        protected void before() throws Throwable
//        {
//        }
//
//        @Override
//        protected void after()
//        {
//        }
//    };
//
//    /**
//     * @throws Exception if any
//     */
//    @Test
//    public void testSomething()
//            throws Exception
//    {
//        File pom = rule.getTestFile( "src/test/resources/unit/project-to-test/pom.xml" );
//        assertNotNull( pom );
//        assertTrue( pom.exists() );
//
//        MyMojo myMojo = (MyMojo) rule.lookupMojo( "touch", pom );
//        assertNotNull( myMojo );
//        myMojo.execute();
//
//        ...
//    }
//
//    /** Do not need the MojoRule. */
//    @WithoutMojo
//    @Test
//    public void testSomethingWhichDoesNotNeedTheMojoAndProbablyShouldBeExtractedIntoANewClassOfItsOwn()
//    {
//      ...
//    }

}
