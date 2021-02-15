package com.vertispan.j2cl.build.provided;

import com.google.auto.service.AutoService;
import com.vertispan.j2cl.build.*;
import net.cardosi.mojo.tools.Javac;

import java.io.File;
import java.nio.file.FileSystems;
import java.nio.file.PathMatcher;
import java.util.List;
import java.util.stream.Collectors;

@AutoService(TaskFactory.class)
public class JavacTask extends TaskFactory {

    public static final PathMatcher JAVA_SOURCES = FileSystems.getDefault().getPathMatcher("glob:**/*.java");
    public static final PathMatcher JAVA_BYTECODE = FileSystems.getDefault().getPathMatcher("glob:**/*.class");

    @Override
    public String getOutputType() {
        return OutputTypes.STRIPPED_BYTECODE;
    }

    @Override
    public String getTaskName() {
        return "default";
    }

    @Override
    public Task resolve(Project project, PropertyTrackingConfig config) {
        // emits only stripped bytecode, so we're not worried about anything other than .java files to compile and .class on the classpath

        Input ownSources = input(project, OutputTypes.STRIPPED_SOURCES).filter(JAVA_SOURCES);
        List<Input> classpathHeaders = scope(project.getDependencies(), Dependency.Scope.COMPILE)
                .stream()
                .map(inputs(OutputTypes.STRIPPED_BYTECODE_HEADERS))
                .map(input -> input.filter(JAVA_BYTECODE))
                .collect(Collectors.toList());

        return outputPath -> {
            File bootstrapClasspath = config.getBootstrapClasspath();
            Javac javac = new Javac(null, classpathHeaders.stream().map(i -> i.resolve(getRegistry()).toFile()).collect(Collectors.toList()), outputPath.toFile(), bootstrapClasspath);

            //TODO convention for mapping to original file paths, provide FileInfo out of Inputs instead of Paths?
            javac.compile(getFileInfoInDir(ownSources.resolve(getRegistry()), FileSystems.getDefault().getPathMatcher("glob:**/*.java")));
        };
    }
}
