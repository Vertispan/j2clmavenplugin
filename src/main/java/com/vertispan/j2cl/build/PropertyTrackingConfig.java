package com.vertispan.j2cl.build;

import com.google.javascript.jscomp.DependencyOptions;
import io.methvin.watcher.hashing.FileHasher;
import io.methvin.watcher.hashing.Murmur3F;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.*;

public class PropertyTrackingConfig {

    private final Map<String, String> config;
    private final Map<String, String> usedKeys = new TreeMap<>();

    private boolean closed = false;

    public PropertyTrackingConfig(Map<String, String> config) {
        this.config = config;
    }

    public void close() {
        closed = true;
    }

    public String getString(String key) {
        checkClosed(key);
        //TODO default handling...
        String value = config.get(key);
        usedKeys.put(key, value);
        return value;
    }

    private void checkClosed(String key) {
        if (closed) {
            throw new IllegalStateException("Can't use config object after it is closed, please retain the config value during setup " + key);
        }
    }

    public File getFile(String key) {
        checkClosed(key);

        File value = new File(config.get(key));

        if (value.exists() && value.isFile()) {
            Murmur3F hash = new Murmur3F();
            try {
                //TODO this assumes that the file can't change - would hate to re-hash it each time...
                hash.update(FileHasher.DEFAULT_FILE_HASHER.hash(value.toPath()).asBytes());
            } catch (IOException e) {
                throw new UncheckedIOException("Failed to hash file contents " + value, e);
            }
            usedKeys.put(key, hash.getValueHexString());
        } else {
            usedKeys.put(key, value.getAbsolutePath());//TODO we're assuming output, out-of-band? should log this
        }
        return value;
    }

    public File getBootstrapClasspath() {
        return getFile(getString("bootstrapClasspath"));
    }

    public String getCompilationLevel() {
        return getString("compilationLevel");
    }

    @Deprecated
    public List<String> getEntrypoint() {
        //TODO support this?
        return null;
    }

    @Deprecated
    public DependencyOptions.DependencyMode getDependencyMode() {
        String value = getString("dependencyMode");
        return DependencyOptions.DependencyMode.valueOf(value);
    }

    public Collection<String> getExterns() {
        //TODO these are files, need to be hashed, or treated as inputs instead?
        return null;
    }

    public boolean getCheckAssertions() {
        return Boolean.parseBoolean(getString("checkAssertions"));
    }

    public boolean getRewritePolyfills() {
        return Boolean.parseBoolean(getString("rewritePolyfills"));
    }

    public boolean getSourcemapsEnabled() {
        return Boolean.parseBoolean(getString("sourcemapsEnabled"));
    }

    public String getInitialScriptFilename() {
        return getString("initialScriptFilename");
    }

    public Map<String, String> getDefines() {
        //TODO this needs to include all the contents, sorted
        return null;
    }

    public Map<String, String> getUsedConfigs() {
        return Collections.unmodifiableMap(usedKeys);
    }

    public List<File> getExtraJsZips() {
        //TODO implement this, perhaps as scope=runtime dependency instead?
        return null;
    }
}
