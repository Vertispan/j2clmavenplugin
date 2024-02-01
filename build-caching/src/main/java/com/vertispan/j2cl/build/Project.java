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

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Represents a set of sources and the dependencies used to build them. The sourceRoots property
 * can contain directories of sources, or a jar containing sources, but not both - if it doesn't
 * point at a source jar, the contents can be watched for changes.
 */
public class Project implements com.vertispan.j2cl.build.task.Project {
    private final String key;

    private List<? extends com.vertispan.j2cl.build.task.Dependency> dependencies;
    private List<String> sourceRoots;

    private boolean isJsZip = false;

    private File jar;

    private Set<String> processors;

    public Project(String key) {
        this.key = key;
    }

    @Override
    public String getKey() {
        return key;
    }

    @Override
    public List<? extends com.vertispan.j2cl.build.task.Dependency> getDependencies() {
        return dependencies;
    }

    public void setDependencies(List<? extends com.vertispan.j2cl.build.task.Dependency> dependencies) {
        this.dependencies = dependencies;
    }

    public List<String> getSourceRoots() {
        return sourceRoots;
    }

    public void setSourceRoots(List<String> sourceRoots) {
        this.sourceRoots = sourceRoots;
    }

    @Override
    public boolean hasSourcesMapped() {
        return sourceRoots.stream().noneMatch(root -> root.endsWith(".jar") || root.endsWith(".zip"));
    }

    @Override
    public String toString() {
        return key;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        Project project = (Project) o;

        return key.equals(project.key);
    }

    @Override
    public int hashCode() {
        return key.hashCode();
    }

    /**
     * Indicate that this Project is actually just a jszip, so should only be unpacked before having
     * closure run on it, but should not actually run javac or j2cl on it.
     *
     * This is an ugly workaround for using bazel outputs in a maven build. That is, we must rely on
     * bazel to build these jars, since the upstream google/j2cl project could change how this is
     * built, and we need to rely on that output being accurate and consistent across versions.
     */
    public void markJsZip() {
        isJsZip = true;
    }

    @Override
    public boolean isJsZip() {
        return isJsZip;
    }

    @Override
    public File getJar() {
        return jar;
    }

    public void setJar(File jar) {
        this.jar = jar;
    }

    @Override
    public Set<String> getProcessors() {
        if (processors == null) {
            if (jar == null || isJsZip() || hasSourcesMapped()) {
                processors = Collections.emptySet();
            } else if (jar.exists()) {
                processors = new HashSet<>();
                try (ZipFile zipFile = new ZipFile(jar)) {
                    ZipEntry entry = zipFile.getEntry("META-INF/services/javax.annotation.processing.Processor");
                    if (entry != null) {
                        try (InputStreamReader inputStreamReader = new InputStreamReader(zipFile.getInputStream(entry), StandardCharsets.UTF_8);
                             BufferedReader bufferedReader = new BufferedReader(inputStreamReader)) {
                            bufferedReader.lines().forEach(line -> processors.add(line.trim()));
                        }
                    }
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            }
        }
        return processors;
    }
}
