package com.vertispan.j2cl.build.provided;

import com.google.auto.service.AutoService;
import com.google.j2cl.common.SourceUtils;
import com.vertispan.j2cl.build.*;
import net.cardosi.mojo.tools.Javac;

import java.io.File;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.List;
import java.util.stream.Collectors;

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

    @Override
    public String getOutputType() {
        return OutputTypes.BYTECODE;
    }

    @Override
    public String getTaskName() {
        return "default";
    }

    @Override
    public Task resolve(Project project, PropertyTrackingConfig config) {
        if (!project.hasSourcesMapped()) {
            // TODO instead copy the bytecode out of the jar so it can be used by downtream bytecode/apt tasks
            return ignore -> {};
        }

        Input inputSources = input(project, OutputTypes.INPUT_SOURCES).filter(JAVA_SOURCES);

        List<Input> bytecodeClasspath = scope(project.getDependencies(), Dependency.Scope.COMPILE)
                .stream()
                .map(inputs(OutputTypes.BYTECODE))
                .collect(Collectors.toList());

        return outputPath -> {
            List<File> classpathDirs = bytecodeClasspath.stream().map(Input::getPath).map(Path::toFile).collect(Collectors.toList());

            // TODO don't dump APT to the same dir?
            Javac javac = new Javac(outputPath.toFile(), classpathDirs, outputPath.toFile(), config.getBootstrapClasspath());

            // TODO convention for mapping to original file paths, provide FileInfo out of Inputs instead of Paths,
            //      automatically relativized?
            Path dir = inputSources.getPath();
            List<SourceUtils.FileInfo> sources = inputSources.getFilesAndHashes()
                    .keySet()
                    .stream()
                    .map(p -> SourceUtils.FileInfo.create(p.toString(), dir.toAbsolutePath().relativize(p).toString()))
                    .collect(Collectors.toList());

            javac.compile(sources);

        };
    }
}
