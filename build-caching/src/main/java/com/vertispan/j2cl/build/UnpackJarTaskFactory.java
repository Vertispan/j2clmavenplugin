/*
 * Copyright © 2021 j2cl-maven-plugin authors
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
package com.vertispan.j2cl.build;

import com.vertispan.j2cl.build.task.Config;
import com.vertispan.j2cl.build.task.Project;
import com.vertispan.j2cl.build.task.TaskFactory;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public class UnpackJarTaskFactory extends TaskFactory {
    @Override
    public String getOutputType() {
        return "unpack";
    }

    @Override
    public String getTaskName() {
        return "unpack";
    }

    @Override
    public String getVersion() {
        return "0";
    }

    @Override
    public Task resolve(Project project, Config config) {
        // we don't have any proper inputs or configs

        // given the first (only) entry in the project's sources, unpack them
        return context -> {
            List<String> sourceRoots = ((com.vertispan.j2cl.build.Project) project).getSourceRoots();
            assert sourceRoots.size() == 1;

            //collect sources from jar instead
            try (ZipFile zipInputFile = new ZipFile(sourceRoots.get(0))) {
                for (ZipEntry z : Collections.list(zipInputFile.entries())) {
                    if (z.isDirectory()) {
                        continue;
                    }
                    Path outPath = context.outputPath().resolve(z.getName());
                    try (InputStream inputStream = zipInputFile.getInputStream(z)) {
                        Files.createDirectories(outPath.getParent());
                        Files.copy(inputStream, outPath);
                    }
                }
            }
        };
    }
}
