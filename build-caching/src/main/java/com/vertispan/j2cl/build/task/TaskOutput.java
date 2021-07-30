package com.vertispan.j2cl.build.task;

import java.nio.file.Path;

public class TaskOutput {
    private final Path path;

    public TaskOutput(Path path) {
        this.path = path;
    }

    public Path path() {
        return path;
    }
}
