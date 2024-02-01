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
package com.vertispan.j2cl.build.task;

import java.nio.file.Path;

/**
 * Describes a file which is hashed and is being read from an upstream task. Name subject to change.
 */
public interface CachedPath {
    /**
     * The relative path of the file - use this when deciding what path to put the file in when copying it
     * to a new directory, or when identifying files to see if their hash changed.
     */
    Path getSourcePath();

    /**
     * The absolute path to the file on disk.
     */
    Path getAbsolutePath();
}
