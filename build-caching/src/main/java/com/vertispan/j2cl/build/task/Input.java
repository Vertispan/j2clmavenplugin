package com.vertispan.j2cl.build.task;

import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.Collection;

public interface Input {
    /**
     * Public API for tasks, builder to limit input scope.
     *
     * Specifies that only part of this input is required. A path entry that matches any
     * of the provided filters will be included.
     *
     * The path parameter given to the matcher will be the relative path of the file within
     * this input - the parent path will not be provided.
     */
    Input filter(PathMatcher... filters);

    /**
     * Public API for tasks.
     *
     * Gets the current files of this input and their hashes that match the filters.
     */
    Collection<? extends CachedPath> getFilesAndHashes();

    /**
     * Public API for tasks.
     *
     * Gets the changed files of this input, with a path for the old and new file, and type of change.
     */
    Collection<? extends ChangedCachedPath> getChanges();

    /**
     * Public API for tasks.
     *
     * Gets the source directories that contain the files offered by this input. Use
     * caution when calling this, as it might contain files that were already filtered
     * out, so could result in inconsistent cache output.
     *
     * For files that come from a task, usually results in zero or one items, for source
     * directories of mapped projects, may result in zero to many items.
     */
    Collection<Path> getParentPaths();

    /**
     * Gets the project that this input comes from.
     */
    Project getProject();
}
