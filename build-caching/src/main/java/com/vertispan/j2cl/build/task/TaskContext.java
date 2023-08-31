package com.vertispan.j2cl.build.task;

import java.nio.file.Path;
import java.util.Optional;

public class TaskContext implements BuildLog {
    private final Path path;
    private final BuildLog log;
    private final Path lastSuccessfulPath;

    public TaskContext(Path path, BuildLog log, Path lastSuccessfulPath) {
        this.path = path;
        this.log = log;
        this.lastSuccessfulPath = lastSuccessfulPath;
    }

    public Path outputPath() {
        return path;
    }

    public BuildLog log() {
        return log;
    }

    /**
     * Returns the output directory from the last time this task ran, to be used to copy other unchanged
     * output files rather than regenerate them.
     *
     * @return empty if no previous build exists, otherwise a path to the last successful output
     */
    public Optional<Path> lastSuccessfulOutput() {
        return Optional.ofNullable(lastSuccessfulPath);
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
