package com.vertispan.j2cl.build.task;

import java.nio.file.Path;
import java.util.Optional;

/**
 * Describes a file that has changed since a previously successful invocation of this task.
 * Analogous to {@link CachedPath}, except there might not be an absolute path for the
 * current file, if it was deleted.
 */
public interface ChangedCachedPath {
    enum ChangeType {
        ADDED,
        REMOVED,
        MODIFIED;
    }

    /**
     * The type of change that took place for this path
     */
    ChangeType changeType();

    /**
     * The path of this file, relative to either its old or new parent.
     */
    Path getSourcePath();

    /**
     * If the file was not deleted, returns the absolute path to the "new" version
     * of this file.
     */
    Optional<Path> getNewAbsolutePath();
}
