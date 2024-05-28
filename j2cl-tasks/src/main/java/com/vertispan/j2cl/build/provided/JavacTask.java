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
import com.google.j2cl.common.SourceUtils;
import com.vertispan.j2cl.build.task.*;
import com.vertispan.j2cl.tools.Javac;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Along with {@link IJarTask}, no longer used unless specifically requested by the user.
 * See {@link TurbineTask} for the more efficient replacement. This class was an intermediate
 * task, before IJarTask, re-enable that class through configuration to use this.
 */
@Deprecated
@AutoService(TaskFactory.class)
public class JavacTask extends TaskFactory {

    public static final PathMatcher JAVA_SOURCES = withSuffix(".java");
    public static final PathMatcher JAVA_BYTECODE = withSuffix(".class");

    @Override
    public String getOutputType() {
        return OutputTypes.STRIPPED_BYTECODE;
    }

    @Override
    public String getTaskName() {
        return "default";
    }

    @Override
    public String getVersion() {
        return "0";
    }

    @Override
    public Task resolve(Project project, Config config) {
        // emits only stripped bytecode, so we're not worried about anything other than .java files to compile and .class on the classpath
        Input ownSources = input(project, OutputTypes.STRIPPED_SOURCES).filter(JAVA_SOURCES);

        List<Input> classpathHeaders = scope(project.getDependencies(), com.vertispan.j2cl.build.task.Dependency.Scope.COMPILE)
                .stream()
                .map(inputs(OutputTypes.STRIPPED_BYTECODE_HEADERS))
                // we only want bytecode _changes_, but we'll use the whole dir
                .map(input -> input.filter(JAVA_BYTECODE))
                .collect(Collectors.toUnmodifiableList());

        File bootstrapClasspath = config.getBootstrapClasspath();
        List<File> extraClasspath = config.getExtraClasspath();
        return context -> {
            if (ownSources.getFilesAndHashes().isEmpty()) {
                return;// no work to do
            }

            List<File> classpathDirs = Stream.concat(
                    classpathHeaders.stream().map(Input::getParentPaths).flatMap(Collection::stream).map(Path::toFile),
                    extraClasspath.stream()
            ).collect(Collectors.toUnmodifiableList());

            List<File> sourcePaths = ownSources.getParentPaths().stream().map(Path::toFile).collect(Collectors.toUnmodifiableList());
            Javac javac = new Javac(context, null, sourcePaths, classpathDirs, context.outputPath().toFile(), bootstrapClasspath, Collections.emptySet(), Collections.emptyMap());

            // TODO convention for mapping to original file paths, provide FileInfo out of Inputs instead of Paths,
            //      automatically relativized?
            List<SourceUtils.FileInfo> sources = ownSources.getFilesAndHashes()
                    .stream()
                    .map(p -> SourceUtils.FileInfo.create(p.getAbsolutePath().toString(), p.getSourcePath().toString()))
                    .collect(Collectors.toUnmodifiableList());

            javac.compile(sources);
        };
    }
}
