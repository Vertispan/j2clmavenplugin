package com.vertispan.j2cl.build.task;

import io.methvin.watcher.hashing.FileHash;

import java.nio.file.Path;

/**
 * Describes a file which is hashed and is being read from an upstream task. Name subject to change.
 */
public interface CachedPath {
    /**
     * The relative path of the file - use this when deciding what path to put the file in when copying it
     * to a new directory, or when identifying files to see if their hash changed.
     */
    Path getSourcePath();

    /**
     * The absolute path to the file on disk.
     */
    Path getAbsolutePath();

    /**
     * The current hash of the file, can be used to diff old and new inputs to see which specific paths changed.
     */
    FileHash getHash();
}
