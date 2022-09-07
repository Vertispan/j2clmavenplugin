package com.vertispan.j2cl.build.task;

import com.vertispan.j2cl.build.BuildService;

import java.nio.file.Path;

public class TaskContext implements BuildLog {
    private final Path path;
    private final BuildLog log;

    private final BuildService buildService;

    public TaskContext(Path path, BuildLog log, BuildService buildService) {
        this.path = path;
        this.log = log;
        this.buildService = buildService;
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

    public BuildService getBuildService() {
        return buildService;
    }
}
