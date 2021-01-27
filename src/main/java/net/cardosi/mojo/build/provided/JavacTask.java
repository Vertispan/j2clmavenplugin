package net.cardosi.mojo.build.provided;

import com.google.auto.service.AutoService;
import net.cardosi.mojo.build.*;
import net.cardosi.mojo.tools.Javac;

import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@AutoService(Task.class)
public class JavacTask extends Task {
    @Override
    public String getOutputType() {
        return OutputTypes.STRIPPED_BYTECODE;
    }

    @Override
    public String getTaskName() {
        return "default";
    }

    @Override
    public List<Input> resolveInputs(Project project) {
        return Stream.concat(
                Stream.of(input(project, OutputTypes.STRIPPED_SOURCES)),
                classpathHeaders(project)
        ).collect(Collectors.toList());
    }

    private Stream<Input> classpathHeaders(Project project) {
        return scope(project.getDependencies(), Dependency.Scope.COMPILE)
                .stream()
                .map(inputs(OutputTypes.STRIPPED_BYTECODE_HEADERS));
    }

    @Override
    public void complete(Project project, Path output) throws Exception {
        // TODO obtain bootstrap classpath from config? some kind of kvp?
        Javac javac = new Javac(null, classpathHeaders(project).map(i -> i.resolve(getRegistry()).toFile()).collect(Collectors.toList()), output.toFile(), null);

        //TODO convention for mapping to original file paths, provide FileInfo out of Inputs instead of Paths?
        javac.compile(getFileInfoInDir(input(project, OutputTypes.STRIPPED_SOURCES).resolve(getRegistry()), FileSystems.getDefault().getPathMatcher("glob:**/*.java")));
    }
}
