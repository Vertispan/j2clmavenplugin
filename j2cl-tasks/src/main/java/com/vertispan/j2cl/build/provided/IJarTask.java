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
package com.vertispan.j2cl.build.provided;

import com.google.auto.service.AutoService;
import com.vertispan.j2cl.build.task.*;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Along with {@link JavacTask}, no longer used unless specifically requested by the user.
 * See {@link TurbineTask} for the more efficient replacement.
 * <p>
 * To re-enable this, set task type stripped_bytecode_headers to "original-bytecode".
 */
@Deprecated
@AutoService(TaskFactory.class)
public class IJarTask extends TaskFactory {
    @Override
    public String getOutputType() {
        return OutputTypes.STRIPPED_BYTECODE_HEADERS;
    }

    @Override
    public String getTaskName() {
        return "original-bytecode";
    }

    @Override
    public String getVersion() {
        return "0";
    }

    @Override
    public Task resolve(Project project, Config config) {
        Input myStrippedBytecode = input(project, OutputTypes.STRIPPED_BYTECODE);
        return context -> {

            // for now we're going to just copy the bytecode
            for (CachedPath path : myStrippedBytecode.getFilesAndHashes()) {
                Path outputFile = context.outputPath().resolve(path.getSourcePath());
                Files.createDirectories(outputFile.getParent());
                Files.copy(path.getAbsolutePath(), outputFile);
            }
        };
    }
}
