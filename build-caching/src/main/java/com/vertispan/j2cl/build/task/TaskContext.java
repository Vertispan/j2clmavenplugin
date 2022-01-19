package com.vertispan.j2cl.build.task;

import java.nio.file.Path;

public class TaskContext implements BuildLog {
    private final Path path;
    private final BuildLog log;

    public TaskContext(Path path, BuildLog log) {
        this.path = path;
        this.log = log;
    }

    public Path outputPath() {
        return path;
    }

    public BuildLog log() {
        return log;
    }

    @Override
    public void debug(String msg) {
        log.debug(msg);
    }

    @Override
    public void info(String msg) {
        log.info(msg);
    }

    @Override
    public void warn(String msg) {
        log.warn(msg);
    }

    @Override
    public void warn(String msg, Throwable t) {
        log.warn(msg, t);
    }

    @Override
    public void warn(Throwable t) {
        log.warn(t);
    }

    @Override
    public void error(String msg) {
        log.error(msg);
    }

    @Override
    public void error(String msg, Throwable t) {
        log.error(msg, t);
    }

    @Override
    public void error(Throwable t) {
        log.error(t);
    }
}
