package net.cardosi.mojo.build.provided;

import com.google.auto.service.AutoService;
import net.cardosi.mojo.build.*;
import net.cardosi.mojo.tools.Javac;

import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * This implementation and {@link AptTask} are wired together, if you replace
 * one you must replace the other at the same time.
 *
 * The assumption is that since these are generated at the same time by a single
 * invocation of javac, we want to generate the bytecode first for downstream
 * projects so they can also generate their own sources. With this though,
 * the AptTask should be a no-op, so it shouldn't really matter.
 */
@AutoService(Task.class)
public class BytecodeTask extends Task {
    @Override
    public String getOutputType() {
        return OutputTypes.BYTECODE;
    }

    @Override
    public String getTaskName() {
        return "default";
    }

    @Override
    public List<Input> resolveInputs(Project project) {
        if (!project.hasSourcesMapped()) {
            // the jar exists, use the existing bytecode
            return Collections.emptyList();
        }
        return Stream.concat(
                Stream.of(input(project, OutputTypes.INPUT_SOURCES)),
                bytecodeClasspath(project)
        ).collect(Collectors.toList());
    }

    private Stream<Input> bytecodeClasspath(Project project) {
        return scope(project.getDependencies(), Dependency.Scope.COMPILE)
                .stream()
                .map(inputs(OutputTypes.BYTECODE));
    }

    @Override
    public void complete(Project project, Path output) throws Exception {
        if (!project.hasSourcesMapped()) {
            // the jar exists, use the existing bytecode
            return;
        }

        // TODO obtain bootstrap classpath from config? some kind of kvp?
        // TODO don't dump to the same dir...
        Javac javac = new Javac(output.toFile(), bytecodeClasspath(project).map(i -> i.resolve(getRegistry()).toFile()).collect(Collectors.toList()), output.toFile(), null);

        //TODO convention for mapping to original file paths, provide FileInfo out of Inputs instead of Paths?
        javac.compile(getFileInfoInDir(input(project, OutputTypes.INPUT_SOURCES).resolve(getRegistry()), FileSystems.getDefault().getPathMatcher("glob:**/*.java")));
    }
}
