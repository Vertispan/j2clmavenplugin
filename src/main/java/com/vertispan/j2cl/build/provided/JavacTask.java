package com.vertispan.j2cl.build.provided;

import com.google.auto.service.AutoService;
import com.vertispan.j2cl.build.*;
import net.cardosi.mojo.tools.Javac;

import java.io.File;
import java.nio.file.FileSystems;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@AutoService(TaskFactory.class)
public class JavacTask extends TaskFactory {
    @Override
    public String getOutputType() {
        return OutputTypes.STRIPPED_BYTECODE;
    }

    @Override
    public String getTaskName() {
        return "default";
    }

    @Override
    public Task resolve(Project project, Config config) {
        Input ownSources = input(project, OutputTypes.STRIPPED_SOURCES);
        Stream<Input> classpathHeaders = scope(project.getDependencies(), Dependency.Scope.COMPILE)
                .stream()
                .map(inputs(OutputTypes.STRIPPED_BYTECODE_HEADERS));

        return outputPath -> {
            File bootstrapClasspath = config.getBootstrapClasspath();
            Javac javac = new Javac(null, classpathHeaders.map(i -> i.resolve(getRegistry()).toFile()).collect(Collectors.toList()), outputPath.toFile(), bootstrapClasspath);

            //TODO convention for mapping to original file paths, provide FileInfo out of Inputs instead of Paths?
            javac.compile(getFileInfoInDir(ownSources.resolve(getRegistry()), FileSystems.getDefault().getPathMatcher("glob:**/*.java")));
        };
    }
}
