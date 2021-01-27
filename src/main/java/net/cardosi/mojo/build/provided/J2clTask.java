package net.cardosi.mojo.build.provided;

import com.google.auto.service.AutoService;
import net.cardosi.mojo.build.*;
import net.cardosi.mojo.tools.J2cl;

import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@AutoService(Task.class)
public class J2clTask extends Task {
    @Override
    public String getOutputType() {
        return OutputTypes.TRANSPILED_JS;
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
        //TODO bootstrap from config?
        J2cl j2cl = new J2cl(classpathHeaders(project).map(i -> i.resolve(getRegistry()).toFile()).collect(Collectors.toList()), null, output.toFile());

        Path sourcesDir = input(project, OutputTypes.STRIPPED_SOURCES).resolve(getRegistry());
        j2cl.transpile(
                getFileInfoInDir(sourcesDir, FileSystems.getDefault().getPathMatcher("glob:**/*.java")),
                getFileInfoInDir(sourcesDir, FileSystems.getDefault().getPathMatcher("glob:**/*.native.js"))
        );
    }
}
