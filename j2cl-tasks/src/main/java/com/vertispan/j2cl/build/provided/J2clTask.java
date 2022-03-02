package com.vertispan.j2cl.build.provided;

import com.google.auto.service.AutoService;
import com.google.j2cl.common.SourceUtils;
import com.vertispan.j2cl.build.BuildService;
import com.vertispan.j2cl.build.ChangedAcceptor;
import com.vertispan.j2cl.build.task.*;
import com.vertispan.j2cl.tools.J2cl;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@AutoService(TaskFactory.class)
public class J2clTask extends TaskFactory {

    public static final PathMatcher JAVA_SOURCES = withSuffix(".java");
    public static final PathMatcher NATIVE_JS_SOURCES = withSuffix(".native.js");
    public static final PathMatcher JAVA_BYTECODE = withSuffix(".class");

    @Override
    public String getOutputType() {
        return OutputTypes.TRANSPILED_JS;
    }

    @Override
    public String getTaskName() {
        return "default";
    }

    @Override
    public String getVersion() {
        return "0";
    }

    @Override
    public Task resolve(Project project, Config config, BuildService buildService) {
        boolean incremental = true;
        // J2CL is only interested in .java and .native.js files in our own sources
        Input ownJavaSources = input(project, OutputTypes.STRIPPED_SOURCES, buildService).filter(JAVA_SOURCES, NATIVE_JS_SOURCES);
        List<Input> ownNativeJsSources = Collections.singletonList(input(project, OutputTypes.BYTECODE, buildService).filter(NATIVE_JS_SOURCES));

        // From our classpath, j2cl is only interested in our compile classpath's bytecode
        List<Project> projects = scope(project.getDependencies(), com.vertispan.j2cl.build.task.Dependency.Scope.COMPILE);

        List<Input> classpathHeaders = projects.stream()
                                               .map(inputs(OutputTypes.STRIPPED_BYTECODE_HEADERS, buildService))
                                               // we only want bytecode _changes_, but we'll use the whole dir
                                               .map(input -> input.filter(JAVA_BYTECODE))
                                               .collect(Collectors.toList());

        File bootstrapClasspath = config.getBootstrapClasspath();
        List<File> extraClasspath = config.getExtraClasspath();
        return context -> {
            List<CachedPath> javaFiles = ownJavaSources.getFilesAndHashes()
                    .stream()
                    .filter( new ChangedAcceptor((com.vertispan.j2cl.build.Project) project, buildService))
                    .collect(Collectors.toList());

            if (javaFiles.isEmpty()) {
                return;// no work to do
            }
            List<File> classpathDirs = Stream.concat(
                    classpathHeaders.stream().flatMap(i -> i.getParentPaths().stream().map(Path::toFile)),
                    extraClasspath.stream()
            )
                    .collect(Collectors.toList());

            if (incremental) {
                Path bytecodePath = buildService.getDiskCache().getLastSuccessfulDirectory(new com.vertispan.j2cl.build.Input((com.vertispan.j2cl.build.Project) project,
                                                                                                                              OutputTypes.BYTECODE));

                if (bytecodePath != null) {
                    classpathDirs.add(bytecodePath.resolve("results").toFile());
                }
            }
            J2cl j2cl = new J2cl(classpathDirs, bootstrapClasspath, context.outputPath().toFile(), context);

            // TODO convention for mapping to original file paths, provide FileInfo out of Inputs instead of Paths,
            //      automatically relativized?
            List<SourceUtils.FileInfo> javaSources = javaFiles.stream().filter(e -> JAVA_SOURCES.matches(e.getSourcePath()))
                    .map(p -> SourceUtils.FileInfo.create(p.getAbsolutePath().toString(), p.getSourcePath().toString()))
                    .collect(Collectors.toList());
            List<SourceUtils.FileInfo> nativeSources = ownNativeJsSources.stream().flatMap(i -> i.getFilesAndHashes().stream())
                    .filter( new ChangedAcceptor((com.vertispan.j2cl.build.Project) project, buildService))
                    .filter(e -> NATIVE_JS_SOURCES.matches(e.getSourcePath()))
                    .map(p -> SourceUtils.FileInfo.create(p.getAbsolutePath().toString(), p.getSourcePath().toString()))
                    .collect(Collectors.toList());

            // TODO when we make j2cl incremental we'll consume the provided sources and hashes (the "values" in the
            //      maps above), and diff them against the previous compile
            if (!j2cl.transpile(javaSources, nativeSources)) {
                throw new IllegalStateException("Error while running J2CL");
            }
        };
    }
}
