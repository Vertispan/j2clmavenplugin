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
package com.vertispan.j2cl.build.task;

import java.nio.file.Path;
import java.util.Optional;

/**
 * Describes a file that has changed since a previously successful invocation of this task.
 * Analogous to {@link CachedPath}, except there might not be an absolute path for the
 * current file, if it was deleted.
 */
public interface ChangedCachedPath {
    enum ChangeType {
        ADDED,
        REMOVED,
        MODIFIED;
    }

    /**
     * The type of change that took place for this path
     */
    ChangeType changeType();

    /**
     * The path of this file, relative to either its old or new parent.
     */
    Path getSourcePath();

    /**
     * If the file was not deleted, returns the absolute path to the "new" version
     * of this file.
     */
    Optional<Path> getNewAbsolutePath();
}
