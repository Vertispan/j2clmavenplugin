/*
 * Copyright © 2022 j2cl-maven-plugin authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
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
