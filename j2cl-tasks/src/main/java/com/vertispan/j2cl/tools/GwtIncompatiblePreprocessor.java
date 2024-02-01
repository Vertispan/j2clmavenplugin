/*
 * Copyright © 2018 j2cl-maven-plugin authors
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
package com.vertispan.j2cl.tools;

import com.google.j2cl.common.OutputUtils;
import com.google.j2cl.common.Problems;
import com.google.j2cl.common.SourceUtils.FileInfo;
import com.google.j2cl.tools.gwtincompatible.GwtIncompatibleStripper;
import com.vertispan.j2cl.build.task.BuildLog;

import java.io.File;
import java.util.List;

/**
 * Takes a directory of sources, and removes any types or members that are
 * annotated with @GwtIncompatible
 */
public class GwtIncompatiblePreprocessor {
    private final File outputDirectory;
    private final BuildLog log;

    public GwtIncompatiblePreprocessor(File outputDirectory, BuildLog log) {
        this.outputDirectory = outputDirectory;
        this.log = log;
        if (!outputDirectory.exists() || !outputDirectory.isDirectory()) {
            throw new IllegalArgumentException(outputDirectory.toString());
        }
    }

    public void preprocess(List<FileInfo> unprocessedFiles) {
        Problems problems = new Problems();

        try (OutputUtils.Output output = OutputUtils.initOutput(outputDirectory.toPath(), problems)) {
            GwtIncompatibleStripper.preprocessFiles(unprocessedFiles, output, problems, "GwtIncompatible");

            if (problems.hasErrors()) {
                throw new IllegalStateException(problems.getErrors().toString());
            }
        } catch (Throwable t) {
            problems.getErrors().forEach(log::error);
            throw t;
        }
    }
}
