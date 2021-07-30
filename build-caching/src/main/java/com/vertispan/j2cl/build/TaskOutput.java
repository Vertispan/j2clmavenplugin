package com.vertispan.j2cl.build;

import java.util.*;

public class TaskOutput {
    private final TreeSet<DiskCache.CacheEntry> relativeFileHashes;

    public TaskOutput(Collection<DiskCache.CacheEntry> relativeFileHashes) {
        this.relativeFileHashes = new TreeSet<>(relativeFileHashes);
    }

    public Collection<DiskCache.CacheEntry> filesAndHashes() {
        return Collections.unmodifiableCollection(relativeFileHashes);
    }
}
