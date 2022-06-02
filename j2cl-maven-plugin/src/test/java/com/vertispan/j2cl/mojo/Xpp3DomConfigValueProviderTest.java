package com.vertispan.j2cl.mojo;

import com.google.inject.AbstractModule;
import com.google.inject.Guice;
import com.google.inject.Inject;
import com.google.inject.Injector;
import com.google.inject.Provides;
import com.google.inject.name.Names;
import com.google.inject.util.Providers;
import com.vertispan.j2cl.build.PropertyTrackingConfig;
import org.apache.maven.artifact.handler.manager.ArtifactHandlerManager;
import org.apache.maven.artifact.handler.manager.DefaultArtifactHandlerManager;
import org.apache.maven.artifact.repository.ArtifactRepository;
import org.apache.maven.artifact.repository.ArtifactRepositoryPolicy;
import org.apache.maven.artifact.repository.MavenArtifactRepository;
import org.apache.maven.artifact.repository.layout.DefaultRepositoryLayout;
import org.apache.maven.eventspy.internal.EventSpyDispatcher;
import org.apache.maven.execution.DefaultMavenExecutionRequest;
import org.apache.maven.internal.aether.DefaultRepositorySystemSessionFactory;
import org.apache.maven.plugin.logging.SystemStreamLog;
import org.apache.maven.repository.internal.MavenAetherModule;
import org.apache.maven.settings.crypto.DefaultSettingsDecrypter;
import org.apache.maven.settings.crypto.SettingsDecrypter;
import org.codehaus.plexus.component.configurator.expression.DefaultExpressionEvaluator;
import org.codehaus.plexus.component.configurator.expression.ExpressionEvaluator;
import org.codehaus.plexus.logging.Logger;
import org.codehaus.plexus.logging.console.ConsoleLogger;
import org.codehaus.plexus.util.xml.Xpp3DomBuilder;
import org.codehaus.plexus.util.xml.pull.XmlPullParserException;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.connector.basic.BasicRepositoryConnectorFactory;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.aether.repository.WorkspaceReader;
import org.eclipse.aether.spi.connector.RepositoryConnectorFactory;
import org.eclipse.aether.spi.connector.transport.TransporterFactory;
import org.eclipse.aether.transport.http.HttpTransporterFactory;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.*;

/**
 * This is a pretty low-quality "unit test" that boostraps large portions of maven's internals
 * to turn on properly. There are probably cleaner ways of testing the ability to resolve a
 * single maven pom to verify that our class functions, but this does work, so we'll use it
 * for now.
 */
public class Xpp3DomConfigValueProviderTest {

    @Inject
    private ExpressionEvaluator expressionEvaluator;
    @Inject
    private RepositorySystemSession repoSession;
    @Inject
    private List<RemoteRepository> repositories;
    @Inject
    private RepositorySystem repoSystem;

    @Rule
    public TemporaryFolder tempDir = new TemporaryFolder();
    @Before
    public void setup() throws IOException {
        File localMavenRepoTempDir = tempDir.newFolder("local-maven-repo");
        Injector injector = Guice.createInjector(
                new MavenAetherModule(),
                new AbstractModule() {
                    @Override
                    protected void configure() {
                        bind(ExpressionEvaluator.class).to(DefaultExpressionEvaluator.class);
                        bind(WorkspaceReader.class).annotatedWith(Names.named("ide")).toProvider(Providers.of(null));
                        bind(SettingsDecrypter.class).to(DefaultSettingsDecrypter.class);
                        bind(Logger.class).to(ConsoleLogger.class);
                        bind(ArtifactHandlerManager.class).to(DefaultArtifactHandlerManager.class);
                    }

                    @Provides
                    EventSpyDispatcher eventSpyDispatcher() {
                        EventSpyDispatcher dispatcher = new EventSpyDispatcher();
                        dispatcher.setEventSpies(Collections.emptyList());
                        return dispatcher;
                    }

                    @Provides
                    RepositorySystemSession repositorySystemSession(DefaultRepositorySystemSessionFactory factory) {
                        DefaultMavenExecutionRequest request = new DefaultMavenExecutionRequest();
                        ArtifactRepository localRepository = new MavenArtifactRepository(
                                org.apache.maven.repository.RepositorySystem.DEFAULT_LOCAL_REPO_ID,
                                "file://" + localMavenRepoTempDir,
                                new DefaultRepositoryLayout(),
                                new ArtifactRepositoryPolicy(true, ArtifactRepositoryPolicy.UPDATE_POLICY_ALWAYS, ArtifactRepositoryPolicy.CHECKSUM_POLICY_IGNORE),
                                new ArtifactRepositoryPolicy(true, ArtifactRepositoryPolicy.UPDATE_POLICY_ALWAYS, ArtifactRepositoryPolicy.CHECKSUM_POLICY_IGNORE)
                        );
                        request.setLocalRepository(localRepository);
                        return factory.newRepositorySession(request);
                    }

                    @Provides
                    Set<RepositoryConnectorFactory> repositoryConnectorFactorySet(BasicRepositoryConnectorFactory connectorFactory) {
                        return Collections.singleton(connectorFactory);
                    }

                    @Provides
                    Set<TransporterFactory> transporterFactorySet(HttpTransporterFactory transporterFactory) {
                        return Collections.singleton(transporterFactory);
                    }

                    @Provides
                    List<RemoteRepository> remoteRepositoryList() {
                        return Collections.singletonList(
                                new RemoteRepository.Builder(
                                        org.apache.maven.repository.RepositorySystem.DEFAULT_REMOTE_REPO_ID,
                                        "default",
                                        org.apache.maven.repository.RepositorySystem.DEFAULT_REMOTE_REPO_URL
                                )
                                        .build()
                        );
                    }
                });
        injector.injectMembers(this);
    }

    /**
     * Helper to take an XML snippet and wrap in an Xpp3Dom instance, and wrap that up (with dependencies)
     * in an Xpp3DomConfigValueProvider for testing.
     */
    private Xpp3DomConfigValueProvider valueProvider(String dom) throws XmlPullParserException, IOException {
        return new Xpp3DomConfigValueProvider(
                Xpp3DomBuilder.build(new StringReader(dom)),
                expressionEvaluator,
                repoSession,
                repositories,
                repoSystem,
                Collections.emptyList(),
                new SystemStreamLog()
        );
    }

    @Test
    public void testFileFromMaven() throws XmlPullParserException, IOException {
        Xpp3DomConfigValueProvider configValueProvider = valueProvider("" +
                "<configuration>" +
                "  <file>org.apache.maven:maven:pom:3.8.2</file>" +
                "</configuration>"
        );

        PropertyTrackingConfig.ConfigValueProvider.ConfigNode node = configValueProvider.findNode("file");
        File file = node.readFile();

        assertTrue(file.exists());
        assertTrue(file.isFile());
    }

    @Test
    public void testFileOnDisk() throws XmlPullParserException, IOException {
        // Create a temp file to use for the test
        Path tempFile = tempDir.newFile("real-file.txt").toPath();
        Files.write(tempFile, "Hello, world".getBytes(StandardCharsets.UTF_8), StandardOpenOption.CREATE);

        Xpp3DomConfigValueProvider configValueProvider = valueProvider("" +
                "<configuration>" +
                "  <file>" + tempFile.toAbsolutePath() + "</file>" +
                "</configuration>"
        );

        PropertyTrackingConfig.ConfigValueProvider.ConfigNode node = configValueProvider.findNode("file");
        File file = node.readFile();

        assertTrue(file.exists());
        assertTrue(file.isFile());
        assertEquals(tempFile.toAbsolutePath().toString(), file.getAbsolutePath());
    }

    @Test
    public void testMissingFile() throws XmlPullParserException, IOException {
        Xpp3DomConfigValueProvider configValueProvider = valueProvider("" +
                "<configuration>" +
                "  <file>doesntexist.txt</file>" +
                "</configuration>"
        );

        // It is safe to read the node, but not the contents of the file - until we
        // actually call readFile(), the node thinks we might call readString() instead.
        PropertyTrackingConfig.ConfigValueProvider.ConfigNode node = configValueProvider.findNode("file");
        try {
            node.readFile();
            fail("Expected exception");
        } catch (IllegalStateException ignored) {
            // exception is expected
        }
    }
}