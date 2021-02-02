package com.vertispan.j2cl.build.provided;

import com.google.auto.service.AutoService;
import com.vertispan.j2cl.build.*;
import net.cardosi.mojo.tools.J2cl;

import java.io.File;
import java.nio.file.FileSystems;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@AutoService(TaskFactory.class)
public class J2clTask extends TaskFactory {
    @Override
    public String getOutputType() {
        return OutputTypes.TRANSPILED_JS;
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
            J2cl j2cl = new J2cl(classpathHeaders.map(i -> i.resolve(getRegistry()).toFile()).collect(Collectors.toList()), bootstrapClasspath, outputPath.toFile());

            j2cl.transpile(
                    getFileInfoInDir(ownSources.resolve(getRegistry()), FileSystems.getDefault().getPathMatcher("glob:**/*.java")),
                    getFileInfoInDir(ownSources.resolve(getRegistry()), FileSystems.getDefault().getPathMatcher("glob:**/*.native.js"))
            );
        };
    }
}
