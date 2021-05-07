package com.vertispan.j2cl.build;

import com.vertispan.j2cl.build.task.Config;
import io.methvin.watcher.hashing.FileHasher;
import io.methvin.watcher.hashing.Murmur3F;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.*;

public class PropertyTrackingConfig implements Config {
    public interface ConfigValueProvider {
        String readStringWithKey(String key);
        File readFileWithKey(String key);
        List<File> readFilesWithKey(String key);
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

    @Override
    public String getString(String key) {
        checkClosed(key);
        //TODO default handling...
        String value = config.readStringWithKey(key);
        usedKeys.put(key, value);
        return value;
    }

    private void checkClosed(String key) {
        if (closed) {
            throw new IllegalStateException("Can't use config object after it is closed, please retain the config value during setup " + key);
        }
    }

    @Override
    public File getFile(String key) {
        checkClosed(key);

        File value = config.readFileWithKey(key);
        if (value == null) {
            usedKeys.put(key, null);
            return null;
        }

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
        //TODO support this?
        return Collections.emptyList();
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
        //TODO this needs to include all the contents, sorted
        return new HashMap<>();
    }

    @Override
    public Map<String, String> getUsedConfigs() {
        return Collections.unmodifiableMap(usedKeys);
    }

    @Override
    public List<File> getExtraJsZips() {
        //TODO implement this, perhaps as scope=runtime dependency instead?
        //TODO hash these
        return config.readFilesWithKey("extraJsZips");
    }
    @Override
    public List<File> getExtraClasspath() {
        //TODO implement this, perhaps as scope=runtime dependency instead?
        //TODO hash these
        return config.readFilesWithKey("extraClasspath");
    }

    @Override
    public String getLanguageOut() {
        return getString("languageOut");
    }
}
