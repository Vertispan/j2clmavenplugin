package com.vertispan.j2cl.build.task;

import java.io.File;
import java.nio.file.Path;
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
    String getDependencyMode();

    Collection<String> getExterns();

    boolean getCheckAssertions();

    boolean getRewritePolyfills();

    boolean getSourcemapsEnabled();

    String getInitialScriptFilename();

    Map<String, String> getDefines();

    Map<String, String> getUsedConfigs();

    String getLanguageOut();

    List<File> getExtraClasspath();

    String getEnv();

    /**
     * This is an output directory, and should not be used an an input for a task,
     * but only for the final step of copying output to the result directory.
     * @return
     */
    Path getWebappDirectory();

}
