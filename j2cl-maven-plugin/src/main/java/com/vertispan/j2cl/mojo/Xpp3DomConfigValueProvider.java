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
import java.util.*;

/**
 * Wraps Maven's Xpp3Dom so dot-separated properties can be accessed.
 */
public class Xpp3DomConfigValueProvider implements PropertyTrackingConfig.ConfigValueProvider {
    private final Xpp3Dom config;
    private final ExpressionEvaluator expressionEvaluator;
    private final RepositorySystemSession repoSession;
    private final List<RemoteRepository> repositories;
    private final RepositorySystem repoSystem;
    private final FileListConfigNode extraClasspath;
    private final FileListConfigNode extraJsZips;

    public Xpp3DomConfigValueProvider(Xpp3Dom config, ExpressionEvaluator expressionEvaluator, RepositorySystemSession repoSession, List<RemoteRepository> repositories, RepositorySystem repoSystem, List<File> extraClasspath, List<File> extraJsZips) {
        this.config = config;
        this.expressionEvaluator = expressionEvaluator;
        this.repoSession = repoSession;
        this.repositories = repositories;
        this.repoSystem = repoSystem;
        this.extraClasspath = new FileListConfigNode("extraClasspath", extraClasspath);
        this.extraJsZips = new FileListConfigNode("extraJsZips", extraJsZips);
        System.out.println(config);
    }

    private File getFileWithMavenCoords(String coords) throws ArtifactResolutionException {
        ArtifactRequest request = new ArtifactRequest()
                .setRepositories(repositories)
                .setArtifact(new DefaultArtifact(coords));

        return repoSystem.resolveArtifact(repoSession, request).getArtifact().getFile();
    }

    class Xpp3DomConfigNode extends AbstractConfigNode {
        private final Xpp3Dom node;

        protected Xpp3DomConfigNode(String path, Xpp3Dom node) {
            super(path);
            this.node = Objects.requireNonNull(node);
        }

        @Override
        public String readString() {
            return getValue(node);
        }

        @Override
        public File readFile() {
            String pathOrCoords = readString();
            if (pathOrCoords == null) {
                return null;
            }
            File f;
            // try to resolve as maven coords first
            try {
                f = getFileWithMavenCoords(pathOrCoords);
            } catch (ArtifactResolutionException e) {
                // handle as a file instead
                f = new File(pathOrCoords);
            }
            System.out.println(getPath() + " => " + f.getAbsolutePath());
            return f;
        }

        @Override
        public String getName() {
            return node.getName();
        }

        @Override
        public List<ConfigNode> getChildren() {
            List<ConfigNode> list = new ArrayList<>();
            Xpp3Dom[] children = node.getChildren();
            for (int i = 0; i < children.length; i++) {
                Xpp3Dom xpp3Dom = children[i];
                Xpp3DomConfigNode xpp3DomConfigNode = new Xpp3DomConfigNode(getPath() + "[" + i + "]" + xpp3Dom.getName(), xpp3Dom);
                list.add(xpp3DomConfigNode);
            }
            return list;
        }
    }
    class FileListConfigNode extends AbstractConfigNode {
        private final List<File> list;

        protected FileListConfigNode(String path, List<File> list) {
            super(path);
            this.list = list;
        }

        @Override
        public String readString() {
            return null;
        }

        @Override
        public File readFile() {
            return null;
        }

        @Override
        public List<ConfigNode> getChildren() {
            List<ConfigNode> nodes = new ArrayList<>();
            for (int i = 0; i < list.size(); i++) {
                nodes.add(new FileConfigNode(getPath() + "[" + i + "]file", list.get(i)));
            }
            return nodes;
        }
    }
    class FileConfigNode extends AbstractConfigNode {
        private final File file;
        protected FileConfigNode(String path, File file) {
            super(path);
            this.file = file;
        }

        @Override
        public String readString() {
            return null;
        }

        @Override
        public File readFile() {
            return file;
        }

        @Override
        public List<ConfigNode> getChildren() {
            return Collections.emptyList();
        }
    }

    @Override
    public ConfigNode findNode(String path) {
        if (path.equals("extraClasspath")) {
            return extraClasspath;
        } else if (path.equals("extraJsZips")) {
            return extraJsZips;
        }
        //uses the path to find the specific node desired
        Xpp3Dom node = findNodeWithKey(config, path, "");
        if (node == null) {
            return null;
        }
        return new Xpp3DomConfigNode(path, node);
    }

    private Xpp3Dom findNodeWithKey(Xpp3Dom config, String prefix, String remaining) {
        assert prefix != null;
        assert remaining != null;
        assert getValue(config) == null;
        // using the longest prefix, look up the key
        Xpp3Dom child = config.getChild(prefix);
        if (child != null) {
            // found it, if we have remaining, we need to handle them
            if (remaining.isEmpty()) {
                // if this is null, that must be expected by the caller
                return child;
            } else {
                return findNodeWithKey(child, remaining, "");
            }
        } else {
            // peel off the last item, and try again
            int index = prefix.lastIndexOf('.');
            if (index == -1) {
                return null;// failed to find it so far, must not be present
            }
            return findNodeWithKey(config, prefix.substring(0, index), prefix.substring(index + 1) + '.' + remaining);
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

    @Override
    public String toString() {
        return this.config.toString();
    }
}
