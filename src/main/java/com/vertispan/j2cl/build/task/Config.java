package com.vertispan.j2cl.build.task;

import com.google.javascript.jscomp.DependencyOptions;

import java.io.File;
import java.util.Collection;
import java.util.List;
import java.util.Map;

public interface Config {
    String getString(String key);

    File getFile(String key);

    File getBootstrapClasspath();

    String getCompilationLevel();

    @Deprecated
    List<String> getEntrypoint();

    @Deprecated
    DependencyOptions.DependencyMode getDependencyMode();

    Collection<String> getExterns();

    boolean getCheckAssertions();

    boolean getRewritePolyfills();

    boolean getSourcemapsEnabled();

    String getInitialScriptFilename();

    Map<String, String> getDefines();

    Map<String, String> getUsedConfigs();

    List<File> getExtraJsZips();

    String getLanguageOut();
}
