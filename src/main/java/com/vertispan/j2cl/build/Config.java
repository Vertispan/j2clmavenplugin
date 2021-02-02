package com.vertispan.j2cl.build;

import com.google.javascript.jscomp.DependencyOptions;

import java.io.File;
import java.util.Collection;
import java.util.List;
import java.util.Map;

public class Config {
    private File bootstrapClasspath;

    public File getBootstrapClasspath() {
        return bootstrapClasspath;
    }

    public void setBootstrapClasspath(File bootstrapClasspath) {
        this.bootstrapClasspath = bootstrapClasspath;
    }

    public String getCompilationLevel() {
        return null;
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
        return false;
    }

    public boolean getRewritePolyfills() {
        return false;
    }

    public boolean getSourcemapsEnabled() {
        return false;
    }

    public String getInitialScriptFilename() {
        return null;
    }

    public Map<String, String> getDefines() {
        return null;
    }
}
