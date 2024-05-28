/*
 * Copyright © 2021 j2cl-maven-plugin authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.vertispan.j2cl.build;

import com.vertispan.j2cl.build.task.Config;
import io.methvin.watcher.hashing.FileHasher;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Collectors;

public class PropertyTrackingConfig implements Config {
    public interface ConfigValueProvider {
        interface ConfigNode {
            String getPath();
            String getName();
            String readString();
            File readFile();
            List<ConfigNode> getChildren();
        }
        abstract class AbstractConfigNode implements ConfigNode {
            private final String path;

            protected AbstractConfigNode(String path) {
                this.path = path;
            }

            @Override
            public String getPath() {
                return path;
            }

            @Override
            public String getName() {
                return getPath().substring(getPath().lastIndexOf('.'));
            }

            @Override
            public String toString() {
                return getClass().getSimpleName() + " path=" + getPath();
            }
        }
        ConfigNode findNode(String path);
    }

    private final ConfigValueProvider config;
    private final Map<String, String> usedKeys = new TreeMap<>();

    private boolean closed = false;

    public PropertyTrackingConfig(ConfigValueProvider config) {
        this.config = config;
    }

    public void close() {
        closed = true;
    }

    private String useStringConfig(ConfigValueProvider.ConfigNode node) {
        String value = node.readString();
        useKey(node.getPath(), value);
        return value;
    }
    private File useFileConfig(ConfigValueProvider.ConfigNode node) {
        File value = node.readFile();
        if (value == null) {
            useKey(node.getPath(), null);
            return null;
        }
        String hash;
        try {
            // TODO this assumes that the file can't change - would hate to re-hash it each time...
            // this is a base64 string, not our "expected" hex
            hash = FileHasher.DEFAULT_FILE_HASHER.hash(value.toPath()).asString();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to hash file contents " + value, e);
        }
        useKey(node.getPath(), "File with hash " + hash);
        return value;
    }
    private void useKey(String path, String value) {
        usedKeys.put(path, value);
    }

    @Override
    public String getString(String key) {
        checkClosed(key);

        ConfigValueProvider.ConfigNode node = config.findNode(key);
        if (node == null) {
            useKey(key, null);
            return null;
        }
        return useStringConfig(node);
    }

    private void checkClosed(String key) {
        if (closed) {
            throw new IllegalStateException("Can't use config object after it is closed, please retain the config value during setup " + key);
        }
    }

    @Override
    public File getFile(String key) {
        checkClosed(key);

        ConfigValueProvider.ConfigNode node = config.findNode(key);
        if (node == null) {
            useKey(key, null);
            return null;
        }
        return useFileConfig(node);
    }

    @Override
    public File getBootstrapClasspath() {
        return getFile("bootstrapClasspath");
    }

    @Override
    public String getCompilationLevel() {
        return getString("compilationLevel");
    }

    @Override
    @Deprecated
    public List<String> getEntrypoint() {
        ConfigValueProvider.ConfigNode entrypoint = config.findNode("entrypoint");
        if (entrypoint == null) {
            return Collections.emptyList();
        }
        return entrypoint.getChildren().stream().map(this::useStringConfig).collect(Collectors.toUnmodifiableList());
    }

    @Override
    @Deprecated
    public String getDependencyMode() {
        return getString("dependencyMode");
    }

    @Override
    public Collection<String> getExterns() {
        //TODO these are files, need to be hashed, or treated as inputs instead?
        return Collections.emptySet();
    }

    @Override
    public boolean getCheckAssertions() {
        return Boolean.parseBoolean(getString("checkAssertions"));
    }

    @Override
    public boolean getRewritePolyfills() {
        return Boolean.parseBoolean(getString("rewritePolyfills"));
    }

    @Override
    public boolean getSourcemapsEnabled() {
        return Boolean.parseBoolean(getString("enableSourcemaps"));
    }

    @Override
    public String getInitialScriptFilename() {
        return getString("initialScriptFilename");
    }

    @Override
    public Map<String, String> getDefines() {
        ConfigValueProvider.ConfigNode defines = config.findNode("defines");
        if (defines == null) {
            return Collections.emptyMap();
        }
        return defines.getChildren().stream()
                // order does not matter, let's make sure the task sees them in the correct order
                .sorted(Comparator.comparing(ConfigValueProvider.ConfigNode::getName))
                // create a map to return - since this is sorted it should be stable both in this tracker and for the consuming task
                .collect(Collectors.toMap(ConfigValueProvider.ConfigNode::getName, this::useStringConfig, (s, s2) -> {
                    throw new IllegalStateException("Two configs found with the same key: " + s + ", s2");
                }, TreeMap::new));
    }

    @Override
    public Map<String, String> getAnnotationProcessorsArgs() {
        ConfigValueProvider.ConfigNode args = config.findNode("annotationProcessorsArgs");
        if (args == null) {
            return Collections.emptyMap();
        }
        return args.getChildren().stream()
                // order does not matter, let's make sure the task sees them in the correct order
                .sorted(Comparator.comparing(ConfigValueProvider.ConfigNode::getName))
                // create a map to return - since this is sorted it should be stable both in this tracker and for the consuming task
                .collect(Collectors.toMap(ConfigValueProvider.ConfigNode::getName, this::useStringConfig, (s, s2) -> {
                    throw new IllegalStateException("Two configs found with the same key: " + s + ", s2");
                }, TreeMap::new));
    }

    @Override
    public Map<String, String> getUsedConfigs() {
        return Collections.unmodifiableMap(usedKeys);
    }

    @Override
    public List<File> getExtraClasspath() {
        //TODO perhaps as scope=runtime dependency instead?
        ConfigValueProvider.ConfigNode extraClasspath = config.findNode("extraClasspath");
        if (extraClasspath == null) {
            return Collections.emptyList();
        }
        return extraClasspath.getChildren().stream()
                .map(node -> {
                    File file = useFileConfig(node);
                    if (file == null) {
                        throw new IllegalStateException("Can't use a null file on the classpath " + node);
                    }
                    return file;
                })
                .collect(Collectors.toUnmodifiableList());
    }

    @Override
    public String getEnv() {
        return getString("env");
    }

    @Override
    public String getLanguageOut() {
        return getString("languageOut");
    }

    @Override
    public Path getWebappDirectory() {
        // Note that this deliberately circumvents the hash building
        ConfigValueProvider.ConfigNode configNode=config.findNode("webappDirectory");
        if (configNode==null) {
            throw new IllegalStateException("No 'webappDirectory' found");
        }
        String s = configNode.readString();
        if (s == null) {
            throw new IllegalStateException("Could not get value of '"+configNode.getPath()+"' from pom.xml in <configuration>");
        }
        return Paths.get(s);
    }

    @Override
    public boolean isIncrementalEnabled() {
        // TODO once we have awesome tests for this, consider skipping the cache. Could be dangerous,
        //      in the case of externally provided buggy tasks.
        return getString("incrementalEnabled").equalsIgnoreCase("true");
    }
}
