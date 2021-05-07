package com.vertispan.j2cl.build.task;

import io.methvin.watcher.hashing.FileHash;

import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.Map;

public interface Input {
    /**
     * Public API for tasks, builder to limit input scope.
     *
     * Specifies that only part of this input is required. A path entry that matches any
     * of the provided filters will be included.
     */
    Input filter(PathMatcher... filters);

    /**
     * Public API for tasks.
     *
     * Provides the whole directory - avoid this if you are using filters, as your task will not
     * get called again for changed files.
     */
    Path getPath();

    /**
     * Public API for tasks.
     *
     * Gets the current files of this input and their hashes that match the filters.
     */
    Map<Path, FileHash> getFilesAndHashes();
}
