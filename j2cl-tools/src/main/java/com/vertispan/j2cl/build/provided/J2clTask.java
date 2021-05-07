package com.vertispan.j2cl.build.provided;

import com.google.auto.service.AutoService;
import com.google.j2cl.common.SourceUtils;
import com.vertispan.j2cl.build.task.*;
import net.cardosi.mojo.tools.J2cl;

import java.io.File;
import java.nio.file.FileSystems;
import java.nio.file.Path;
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
    public Task resolve(Project project, Config config) {
        // J2CL is only interested in .java and .native.js files in our own sources
        Input ownSources = input(project, OutputTypes.STRIPPED_SOURCES).filter(JAVA_SOURCES, NATIVE_JS_SOURCES);

        // From our classpath, j2cl is only interested in our compile classpath's bytecode
        List<Input> classpathHeaders = scope(project.getDependencies(), com.vertispan.j2cl.build.task.Dependency.Scope.COMPILE)
                .stream()
                .map(inputs(OutputTypes.STRIPPED_BYTECODE_HEADERS))
                // we only want bytecode _changes_, but we'll use the whole dir
                .map(input -> input.filter(JAVA_BYTECODE))
                .collect(Collectors.toList());

        File bootstrapClasspath = config.getBootstrapClasspath();
        return outputPath -> {
            List<File> classpathDirs = classpathHeaders.stream()
                    .map(i -> i.getPath().toFile())
                    .collect(Collectors.toList());

            J2cl j2cl = new J2cl(classpathDirs, bootstrapClasspath, outputPath.toFile());

            // TODO convention for mapping to original file paths, provide FileInfo out of Inputs instead of Paths,
            //      automatically relativized?
            Path dir = ownSources.getPath();
            List<SourceUtils.FileInfo> javaSources = ownSources.getFilesAndHashes()
                    .keySet()
                    .stream()
                    .filter(JAVA_SOURCES::matches)
                    .map(p -> SourceUtils.FileInfo.create(dir.toAbsolutePath().resolve(p).toString(), p.toString()))
                    .collect(Collectors.toList());
            List<SourceUtils.FileInfo> nativeSources = ownSources.getFilesAndHashes()
                    .keySet()
                    .stream()
                    .filter(NATIVE_JS_SOURCES::matches)
                    .map(p -> SourceUtils.FileInfo.create(dir.toAbsolutePath().resolve(p).toString(), p.toString()))
                    .collect(Collectors.toList());

            // TODO when we make j2cl incremental we'll consume the provided sources and hashes (the "values" in the
            //      maps above), and diff them against the previous compile
            j2cl.transpile(
                    javaSources,
                    nativeSources
            );
        };
    }
}
