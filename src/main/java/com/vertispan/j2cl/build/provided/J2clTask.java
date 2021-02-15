package com.vertispan.j2cl.build.provided;

import com.google.auto.service.AutoService;
import com.vertispan.j2cl.build.*;
import net.cardosi.mojo.tools.J2cl;

import java.io.File;
import java.nio.file.FileSystems;
import java.nio.file.PathMatcher;
import java.util.List;
import java.util.stream.Collectors;

@AutoService(TaskFactory.class)
public class J2clTask extends TaskFactory {

    public static final PathMatcher JAVA_SOURCES = FileSystems.getDefault().getPathMatcher("glob:**/*.java");
    public static final PathMatcher NATIVE_JS_SOURCES = FileSystems.getDefault().getPathMatcher("glob:**/*.native.js");
    public static final PathMatcher JAVA_BYTECODE = FileSystems.getDefault().getPathMatcher("glob:**/*.class");

    @Override
    public String getOutputType() {
        return OutputTypes.TRANSPILED_JS;
    }

    @Override
    public String getTaskName() {
        return "default";
    }

    @Override
    public Task resolve(Project project, PropertyTrackingConfig config) {
        // J2CL is only interested in .java and .native.js files in our own sources
        Input ownSources = input(project, OutputTypes.STRIPPED_SOURCES).filter(JAVA_SOURCES, NATIVE_JS_SOURCES);

        // From our classpath, j2cl is only interested in our compile classpath's bytecode
        List<Input> classpathHeaders = scope(project.getDependencies(), Dependency.Scope.COMPILE)
                .stream()
                .map(inputs(OutputTypes.STRIPPED_BYTECODE_HEADERS))
                .map(input -> input.filter(JAVA_BYTECODE))
                .collect(Collectors.toList());

        return outputPath -> {
            File bootstrapClasspath = config.getBootstrapClasspath();
            J2cl j2cl = new J2cl(classpathHeaders.stream().map(i -> i.resolve(getRegistry()).toFile()).collect(Collectors.toList()), bootstrapClasspath, outputPath.toFile());

            j2cl.transpile(
                    getFileInfoInDir(ownSources.resolve(getRegistry()), JAVA_SOURCES),
                    getFileInfoInDir(ownSources.resolve(getRegistry()), NATIVE_JS_SOURCES)
            );
        };
    }
}
