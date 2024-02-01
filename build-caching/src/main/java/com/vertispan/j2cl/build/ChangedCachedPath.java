/*
 * Copyright © 2023 j2cl-maven-plugin authors
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

import com.vertispan.j2cl.build.task.CachedPath;

import java.nio.file.Path;
import java.util.Optional;

/**
 * Implementation of the ChangedCachedPath interface.
 */
public class ChangedCachedPath implements com.vertispan.j2cl.build.task.ChangedCachedPath {
    private final ChangeType type;
    private final Path sourcePath;
    private final CachedPath newIfAny;

    public ChangedCachedPath(ChangeType type, Path sourcePath, CachedPath newPath) {
        this.type = type;
        this.sourcePath = sourcePath;
        this.newIfAny = newPath;
    }

    @Override
    public ChangeType changeType() {
        return type;
    }

    @Override
    public Path getSourcePath() {
        return sourcePath;
    }

    @Override
    public Optional<Path> getNewAbsolutePath() {
        return Optional.ofNullable(newIfAny).map(CachedPath::getAbsolutePath);
    }
}
