package com.vertispan.j2cl.build.provided;

import com.google.auto.service.AutoService;
import com.google.j2cl.common.SourceUtils;
import com.vertispan.j2cl.build.task.*;
import com.vertispan.j2cl.tools.Javac;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * This implementation and {@link AptTask} are wired together, if you replace
 * one you may need to replace the other at the same time (the SkipAptTask is an
 * exception to this).
 *
 * The assumption is that since these are generated at the same time by a single
 * invocation of javac, we want to generate the bytecode first for downstream
 * projects so they can also generate their own sources. With this though,
 * the AptTask should be a no-op, so it shouldn't really matter.
 */
@AutoService(TaskFactory.class)
public class BytecodeTask extends TaskFactory {

    public static final PathMatcher JAVA_SOURCES = withSuffix(".java");
    public static final PathMatcher JAVA_BYTECODE = withSuffix(".class");
    public static final PathMatcher NOT_BYTECODE = p -> !JAVA_BYTECODE.matches(p);

    @Override
    public String getOutputType() {
        return OutputTypes.BYTECODE;
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
        if (!project.hasSourcesMapped()) {
            // instead copy the bytecode out of the jar so it can be used by downtream bytecode/apt tasks
            Input existingUnpackedBytecode = input(project, OutputTypes.INPUT_SOURCES);//.filter(JAVA_BYTECODE);
            return context -> {
                for (CachedPath entry : existingUnpackedBytecode.getFilesAndHashes()) {
                    Files.createDirectories(context.outputPath().resolve(entry.getSourcePath()).getParent());
                    Files.copy(entry.getAbsolutePath(), context.outputPath().resolve(entry.getSourcePath()));
                }
            };
        }

        // TODO just use one input for both of these
        // track the dirs (with all file changes) so that APT can see things it wants
        Input inputDirs = input(project, OutputTypes.INPUT_SOURCES);
        // track just java files (so we can just compile them)
        Input inputSources = input(project, OutputTypes.INPUT_SOURCES).filter(JAVA_SOURCES);
        // track resources so they are available to downstream processors on the classpath, as they would
        // be if we had built a jar
        Input resources = input(project, OutputTypes.INPUT_SOURCES).filter(NOT_BYTECODE);

        List<Input> bytecodeClasspath = scope(project.getDependencies(), com.vertispan.j2cl.build.task.Dependency.Scope.COMPILE)
                .stream()
                .map(inputs(OutputTypes.BYTECODE))
                .collect(Collectors.toList());

        File bootstrapClasspath = config.getBootstrapClasspath();
        List<File> extraClasspath = config.getExtraClasspath();
        return context -> {
            if (inputSources.getFilesAndHashes().isEmpty()) {
                return;// no work to do
            }

            List<File> classpathDirs = Stream.concat(
                    bytecodeClasspath.stream().map(Input::getParentPaths).flatMap(Collection::stream).map(Path::toFile),
                    extraClasspath.stream()
            ).collect(Collectors.toList());

            // TODO don't dump APT to the same dir?
            List<File> sourcePaths = inputDirs.getParentPaths().stream().map(Path::toFile).collect(Collectors.toList());
            Javac javac = new Javac(context, context.outputPath().toFile(), sourcePaths, classpathDirs, context.outputPath().toFile(), bootstrapClasspath);

            // TODO convention for mapping to original file paths, provide FileInfo out of Inputs instead of Paths,
            //      automatically relativized?
            List<SourceUtils.FileInfo> sources = inputSources.getFilesAndHashes()
                    .stream()
                    .map(p -> SourceUtils.FileInfo.create(p.getAbsolutePath().toString(), p.getSourcePath().toString()))
                    .collect(Collectors.toList());

            try {
                if (!javac.compile(sources)) {
                    throw new RuntimeException("Failed to complete bytecode task, check log");
                }
                for (CachedPath entry : resources.getFilesAndHashes()) {
                    Files.createDirectories(context.outputPath().resolve(entry.getSourcePath()).getParent());
                    Files.copy(entry.getAbsolutePath(), context.outputPath().resolve(entry.getSourcePath()));
                }
            } catch (Exception exception) {
                exception.printStackTrace();
                throw exception;
            }

        };
    }
}
