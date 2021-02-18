package com.vertispan.j2cl.build;

import io.methvin.watcher.hashing.FileHash;

import java.nio.file.Path;
import java.util.Collection;
import java.util.Map;
import java.util.TreeMap;


public class TaskOutput {
    private final String cacheKey;
    private final Path path;
    private final TreeMap<Path, FileHash> relativeFileHashes = new TreeMap<>();

    public TaskOutput(String cacheKey, Path path) {
        this.cacheKey = cacheKey;
        this.path = path;
    }

    public Collection<Map.Entry<Path, FileHash>> filesAndHashes() {
        return relativeFileHashes.entrySet();
    }

    public Path getPath() {
        return path;
    }
}
