package com.vertispan.j2cl.build.provided;

import com.google.auto.service.AutoService;
import com.vertispan.j2cl.build.*;
import net.cardosi.mojo.tools.Javac;

import java.nio.file.FileSystems;
import java.util.List;
import java.util.stream.Collectors;

/**
 * This implementation and {@link AptTask} are wired together, if you replace
 * one you must replace the other at the same time.
 *
 * The assumption is that since these are generated at the same time by a single
 * invocation of javac, we want to generate the bytecode first for downstream
 * projects so they can also generate their own sources. With this though,
 * the AptTask should be a no-op, so it shouldn't really matter.
 */
@AutoService(TaskFactory.class)
public class BytecodeTask extends TaskFactory {
    @Override
    public String getOutputType() {
        return OutputTypes.BYTECODE;
    }

    @Override
    public String getTaskName() {
        return "default";
    }

    @Override
    public Task resolve(Project project, Config config) {
        if (!project.hasSourcesMapped()) {
            // TODO instead copy the bytecode?
            return ignore -> {};
        }

        Input inputSources = input(project, OutputTypes.INPUT_SOURCES);

        List<Input> bytecodeClasspath = scope(project.getDependencies(), Dependency.Scope.COMPILE)
                .stream()
                .map(inputs(OutputTypes.BYTECODE)).collect(Collectors.toList());

        return outputPath -> {
            // TODO don't dump to the same dir...
            Javac javac = new Javac(outputPath.toFile(), bytecodeClasspath.stream().map(i -> i.resolve(getRegistry()).toFile()).collect(Collectors.toList()), outputPath.toFile(), config.getBootstrapClasspath());

            //TODO convention for mapping to original file paths, provide FileInfo out of Inputs instead of Paths?
            javac.compile(getFileInfoInDir(inputSources.resolve(getRegistry()), FileSystems.getDefault().getPathMatcher("glob:**/*.java")));

        };
    }
}
