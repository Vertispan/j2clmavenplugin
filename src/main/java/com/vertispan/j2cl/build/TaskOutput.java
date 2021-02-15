package com.vertispan.j2cl.build;

import java.nio.file.Path;


public class TaskOutput {
    private final String cacheKey;
    private final Path path;

    public TaskOutput(String cacheKey, Path path) {
        this.cacheKey = cacheKey;
        this.path = path;
    }
}
