package com.vertispan.j2cl.build;

import com.google.javascript.jscomp.DependencyOptions;

import java.io.File;
import java.util.*;

public class PropertyTrackingConfig {

    private final Map<String, String> config;
    private final Set<String> usedKeys = new HashSet<>();

    public PropertyTrackingConfig(Map<String, String> config) {

        this.config = config;
    }

    public String get(String key) {
        //TODO default handling...
        usedKeys.add(key);
        return config.get(key);
    }


    public File getBootstrapClasspath() {
        return new File(get("bootstrapClasspath"));
    }

    public String getCompilationLevel() {
        return get("compilationLevel");
    }

    @Deprecated
    public List<String> getEntrypoint() {
        return null;
    }

    @Deprecated
    public DependencyOptions.DependencyMode getDependencyMode() {
        return null;
    }

    public Collection<String> getExterns() {
        return null;
    }

    public boolean getCheckAssertions() {
        return Boolean.parseBoolean(get("checkAssertions"));
    }

    public boolean getRewritePolyfills() {
        return Boolean.parseBoolean(get("rewritePolyfills"));
    }

    public boolean getSourcemapsEnabled() {
        return Boolean.parseBoolean(get("sourcemapsEnabled"));
    }

    public String getInitialScriptFilename() {
        return get("initialScriptFilename");
    }

    public Map<String, String> getDefines() {
        return null;
    }

    public Map<String, String> getUsedConfigs() {
        Map<String, String> used = new HashMap<>(config);
        used.keySet().retainAll(usedKeys);
        return used;
    }
}
