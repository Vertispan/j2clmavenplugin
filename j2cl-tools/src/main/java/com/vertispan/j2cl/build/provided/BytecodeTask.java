package com.vertispan.j2cl.build.provided;

import com.google.auto.service.AutoService;
import com.google.j2cl.common.SourceUtils;
import com.vertispan.j2cl.build.BuildService;
import com.vertispan.j2cl.build.ChangedAcceptor;
import com.vertispan.j2cl.build.DiskCache;
import com.vertispan.j2cl.build.task.*;
import com.vertispan.j2cl.tools.Javac;

import java.io.File;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.Collection;
import java.util.List;
import java.util.function.Function;
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

    public static final PathMatcher JAVA_SOURCES = FileSystems.getDefault().getPathMatcher("glob:**/*.java");
    public static final PathMatcher JAVA_BYTECODE = FileSystems.getDefault().getPathMatcher("glob:**/*.class");

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
    public Task resolve(Project project, Config config, BuildService buildService) {
        boolean incremental = true;
        if (!project.hasSourcesMapped()) {
            // instead copy the bytecode out of the jar so it can be used by downtream bytecode/apt tasks
            Input existingUnpackedBytecode = input(project, OutputTypes.INPUT_SOURCES, buildService);//.filter(JAVA_BYTECODE);
            return (output)   -> {
                for (CachedPath entry : existingUnpackedBytecode.getFilesAndHashes()) {
                    Files.createDirectories(output.path().resolve(entry.getSourcePath()).getParent());
                    Files.copy(entry.getAbsolutePath(), output.path().resolve(entry.getSourcePath()));
                }
            };
        }

        // TODO just use one input for both of these
        // track the dirs (with all file changes) so that APT can see things it wants
        Input inputDirs = input(project, OutputTypes.INPUT_SOURCES, buildService);
        // track just java files (so we can just compile them)
        Input inputSources = input(project, OutputTypes.INPUT_SOURCES, buildService).filter(JAVA_SOURCES);

        List<Input> bytecodeClasspath = scope(project.getDependencies(), com.vertispan.j2cl.build.task.Dependency.Scope.COMPILE)
                .stream()
                .map(inputs(OutputTypes.BYTECODE, buildService))
                .collect(Collectors.toList());

        File bootstrapClasspath = config.getBootstrapClasspath();
        List<File> extraClasspath = config.getExtraClasspath();
        return (output)  -> {
            List<CachedPath> files = inputSources.getFilesAndHashes()
                                                 .stream()
                                                 .filter( new ChangedAcceptor((com.vertispan.j2cl.build.Project) project, buildService)).collect(Collectors.toList());

            if (files.isEmpty()) {
                return;// no work to do
            }

            bytecodeClasspath.stream().map(Input::getParentPaths).flatMap(Collection::stream).map(Path::toFile).forEach(
                  f -> System.out.println("bytecodepath: " + f.getAbsolutePath().toString()));

            List<File> classpathDirs = Stream.of(
                    bytecodeClasspath.stream().map(Input::getParentPaths).flatMap(Collection::stream).map(Path::toFile),
                    extraClasspath.stream() //,
//                    inputDirs.getParentPaths().stream().map(Path::toFile)
            )
                    .flatMap(Function.identity())
                    .collect(Collectors.toList());

            if (incremental) {
                Path bytecodePath = buildService.getDiskCache().getLastSuccessfulDirectory(new com.vertispan.j2cl.build.Input((com.vertispan.j2cl.build.Project) project,
                                                                                                                              OutputTypes.BYTECODE));

                if (bytecodePath != null) {
                    classpathDirs.add(bytecodePath.resolve("results").toFile());
                }
            }

            // TODO don't dump APT to the same dir?
            Javac javac = new Javac(output.path().toFile(), classpathDirs, output.path().toFile(), bootstrapClasspath);

            // TODO convention for mapping to original file paths, provide FileInfo out of Inputs instead of Paths,
            //      automatically relativized?
            List<SourceUtils.FileInfo> sources = files.stream()
                                                      .map(p -> SourceUtils.FileInfo.create(p.getAbsolutePath().toString(), p.getSourcePath().toString()))
                    .collect(Collectors.toList());

            try {
                if (!javac.compile(sources)) {
                    throw new RuntimeException("Failed to complete bytecode task, check log");
                }
            } catch (Exception exception) {
                exception.printStackTrace();
                throw exception;
            }

        };
    }
}
