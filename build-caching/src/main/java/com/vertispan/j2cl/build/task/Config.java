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

    /**
     * Allow tasks to know if they need to do the work to build incrementally. Tasks
     * should decide for themselves what inputs they might need or what work they
     * might do based on this, plus if sources of a given project are mapped or not.
     * <p></p>
     * For example, if incremental is cheap, might as well always do it, if not, only
     * do it if all markers suggest it is a good idea. However, if this flag is false,
     * incremental should never be attempted (could be a bug in it, etc).
     *
     * @return true if incremental is enabled, false if it should be skipped
     */
    boolean isIncrementalEnabled();

}
