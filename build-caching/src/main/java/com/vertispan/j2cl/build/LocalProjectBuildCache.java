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

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.Comparator;
import java.util.Optional;

public class LocalProjectBuildCache {
    private final File cacheDir;
    private final DiskCache cache;


    public LocalProjectBuildCache(File cacheDir, DiskCache cache) {
        this.cacheDir = cacheDir;
        this.cache = cache;
    }

    public void markLocalSuccess(Project project, String task, Path taskDir) {
        String timestamp = String.valueOf(System.currentTimeMillis());
        try {
            Path localTaskDir = cacheDir.toPath().resolve(project.getKey().replaceAll("[^\\-_a-zA-Z0-9.]", "-")).resolve(task);
            Files.createDirectories(localTaskDir);
            Files.write(localTaskDir.resolve(timestamp), Collections.singleton(taskDir.toString()));

            Files.list(localTaskDir).sorted(Comparator.<Path>naturalOrder().reversed()).skip(5).forEach(old -> {
                try {
                    Files.delete(old);
                } catch (IOException e) {
                    // we don't care at all about failure, as long as it sometimes works. there could be races
                    // to delete, or someone just cleaned, etc.
                    e.printStackTrace();
                }
            });
        } catch (IOException e) {
            // ignore, cache just won't work for now
            //TODO log this, the user will be irritated
            e.printStackTrace();
        }
    }

    public Optional<DiskCache.CacheResult> getLatestResult(Project project, String task) {
        try {
            Path taskDir = cacheDir.toPath().resolve(project.getKey().replaceAll("[^\\-_a-zA-Z0-9.]", "-")).resolve(task);
            Optional<Path> latest = Files.list(taskDir).max(Comparator.naturalOrder());
            if (latest.isPresent()) {
                String path = Files.readAllLines(latest.get()).get(0);

                return cache.getCacheResult(Paths.get(path));
            } else {
                return Optional.empty();
            }
        } catch (IOException e) {
            // Probably file doesn't exist. This seems terrible and dirty... but on the other hand, just about any
            // failure to read could mean "look just go do it from scratch, since that is always possible".
            return Optional.empty();
        }
    }

}
