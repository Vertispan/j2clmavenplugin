package com.vertispan.j2cl.build;

import io.methvin.watcher.hashing.FileHash;

import java.nio.file.Path;
import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;

public class TaskOutput {
    private final Path path;
    private final TreeMap<Path, FileHash> relativeFileHashes;

    public TaskOutput(Path path, Map<Path, FileHash> relativeFileHashes) {
        this.path = path;
        this.relativeFileHashes = new TreeMap<>(relativeFileHashes);
    }

    public Collection<Map.Entry<Path, FileHash>> filesAndHashes() {
        return Collections.unmodifiableCollection(relativeFileHashes.entrySet());
    }

    public Path getPath() {
        return path;
    }
}
