package com.vertispan.j2cl.mojo;

import com.vertispan.j2cl.build.PropertyTrackingConfig;
import org.codehaus.plexus.component.configurator.expression.ExpressionEvaluationException;
import org.codehaus.plexus.component.configurator.expression.ExpressionEvaluator;
import org.codehaus.plexus.util.xml.Xpp3Dom;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.aether.resolution.ArtifactRequest;
import org.eclipse.aether.resolution.ArtifactResolutionException;

import java.io.File;
import java.util.List;
import java.util.Objects;

/**
 * Wraps Maven's Xpp3Dom so dot-separated properties can be accessed.
 */
public class Xpp3DomConfigValueProvider implements PropertyTrackingConfig.ConfigValueProvider {
    private final Xpp3Dom config;
    private final ExpressionEvaluator expressionEvaluator;
    private final RepositorySystemSession repoSession;
    private final List<RemoteRepository> repositories;
    private final RepositorySystem repoSystem;
    private final List<File> extraClasspath;
    private final List<File> extraJsZips;

    public Xpp3DomConfigValueProvider(Xpp3Dom config, ExpressionEvaluator expressionEvaluator, RepositorySystemSession repoSession, List<RemoteRepository> repositories, RepositorySystem repoSystem, List<File> extraClasspath, List<File> extraJsZips) {
        this.config = config;
        this.expressionEvaluator = expressionEvaluator;
        this.repoSession = repoSession;
        this.repositories = repositories;
        this.repoSystem = repoSystem;
        this.extraClasspath = extraClasspath;
        this.extraJsZips = extraJsZips;
        System.out.println(config);
    }

    private File getFileWithMavenCoords(String coords) throws ArtifactResolutionException {
        ArtifactRequest request = new ArtifactRequest()
                .setRepositories(repositories)
                .setArtifact(new DefaultArtifact(coords));

        return repoSystem.resolveArtifact(repoSession, request).getArtifact().getFile();
    }

    @Override
    public String readStringWithKey(String key) {
        String result = readValueWithKey(config, Objects.requireNonNull(key, "key"), "");
        System.out.println(key + " => " + result);
        return result;
    }

    @Override
    public File readFileWithKey(String key) {
        String pathOrCoords = readValueWithKey(config, Objects.requireNonNull(key, "key"), "");
        if (pathOrCoords == null) {
            return null;
        }
        File f;
        // try to resolve as a coords first
        try {
            f = getFileWithMavenCoords(pathOrCoords);
        } catch (ArtifactResolutionException e) {
            // handle as a file instead
            f = new File(key);
        }
        System.out.println(key + " => " + f.getAbsolutePath());
        return f;
    }

    @Override
    public List<File> readFilesWithKey(String key) {
        //FIXME cheaty method to deal with some extra configs that don't make sense yet
        switch (key) {
            //TODO hash these
            case "extraClasspath":
                return extraClasspath;
            case "extraJsZips":
                return extraJsZips;
        }
        return null;
    }

    // this method built to use tail-call recursion by hand, then automatically updated to use iteration


    //original, update this first and generate
    private String readValueWithKey(Xpp3Dom config, String prefix, String remaining) {
        assert prefix != null;
        assert remaining != null;
        assert getValue(config) == null;
        // using the longest prefix, look up the key
        Xpp3Dom child = config.getChild(prefix);
        if (child != null) {
            // found it, if we have remaining, we need to handle them
            if (remaining.isEmpty()) {
                // if this is null, that must be expected by the caller
                return getValue(child);
            } else {
                return readValueWithKey(child, remaining, "");
            }
        } else {
            // peel off the last item, and try again
            int index = prefix.lastIndexOf('.');
            if (index == -1) {
                return null;// failed to find it so far, must not be present
            }
            return readValueWithKey(config, prefix.substring(0, index), prefix.substring(index + 1) + '.' + remaining);
        }
    }

    private String getValue(Xpp3Dom child) {
        String value = child.getValue();
        String result = null;
        try {
            if (value != null) {
                result = (String) expressionEvaluator.evaluate(value);
            }
            if (result == null) {
                value = child.getAttribute("default-value");
                result = (String) expressionEvaluator.evaluate(value);
            }
        } catch (ExpressionEvaluationException e) {
            throw new IllegalArgumentException(e);
        }
        return result;
    }

}
