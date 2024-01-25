package com.vertispan.j2cl.build.provided;

import com.google.auto.service.AutoService;
import com.google.j2cl.common.SourceUtils;
import com.google.javascript.jscomp.CompilationLevel;
import com.vertispan.j2cl.build.task.*;
import com.vertispan.j2cl.tools.Closure;
import com.vertispan.j2cl.tools.J2cl;
import org.apache.commons.io.FileUtils;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
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
    public Task resolve(Project project, Config config) {
        // J2CL is only interested in .java and .native.js files in our own sources
        Input ownJavaSources = input(project, OutputTypes.STRIPPED_SOURCES).filter(JAVA_SOURCES, NATIVE_JS_SOURCES);
        List<Input> ownNativeJsSources = Collections.singletonList(input(project, OutputTypes.BYTECODE).filter(NATIVE_JS_SOURCES));

        // From our classpath, j2cl is only interested in our compile classpath's bytecode
        List<Input> classpathHeaders = scope(project.getDependencies().stream()
                .filter(dep -> dep.getProject().getProcessors().isEmpty())
                .collect(Collectors.toSet()), com.vertispan.j2cl.build.task.Dependency.Scope.COMPILE)
                .stream()
                .map(inputs(OutputTypes.STRIPPED_BYTECODE_HEADERS))
                // we only want bytecode _changes_, but we'll use the whole dir
                .map(input -> input.filter(JAVA_BYTECODE))
                .collect(Collectors.toUnmodifiableList());

        File bootstrapClasspath = config.getBootstrapClasspath();
        List<File> extraClasspath = config.getExtraClasspath();

        boolean sourcemapsEnabled = config.getSourcemapsEnabled() && config.getEnableIncrementalSourcemaps();
        Path sourceMapsFolder = sourcemapsEnabled ? prepareSourcesFolder(config, project) : null;

        return context -> {
            if (ownJavaSources.getFilesAndHashes().isEmpty()) {
                return;// nothing to do
            }
            List<File> classpathDirs = Stream.concat(
                    classpathHeaders.stream().flatMap(i -> i.getParentPaths().stream().map(Path::toFile)),
                    extraClasspath.stream()
            )
                    .collect(Collectors.toUnmodifiableList());

            J2cl j2cl = new J2cl(classpathDirs, bootstrapClasspath, context.outputPath().toFile(), context);

            // TODO convention for mapping to original file paths, provide FileInfo out of Inputs instead of Paths,
            //      automatically relativized?
            List<SourceUtils.FileInfo> javaSources = ownJavaSources.getFilesAndHashes()
                    .stream()
                    .filter(e -> JAVA_SOURCES.matches(e.getSourcePath()))
                    .map(p -> SourceUtils.FileInfo.create(p.getAbsolutePath().toString(), p.getSourcePath().toString()))
                    .collect(Collectors.toUnmodifiableList());
            List<SourceUtils.FileInfo> nativeSources = ownNativeJsSources.stream().flatMap(i ->
                    i.getFilesAndHashes()
                            .stream())
                    .filter(e -> NATIVE_JS_SOURCES.matches(e.getSourcePath()))
                    .map(p -> SourceUtils.FileInfo.create(p.getAbsolutePath().toString(), p.getSourcePath().toString()))
                    .collect(Collectors.toUnmodifiableList());

            // TODO when we make j2cl incremental we'll consume the provided sources and hashes (the "values" in the
            //      maps above), and diff them against the previous compile
            if (!j2cl.transpile(javaSources, nativeSources)) {
                throw new IllegalStateException("Error while running J2CL");
            }

            if(sourcemapsEnabled) {
                if(sourceMapsFolder.toFile().exists()) {
                    FileUtils.deleteDirectory(sourceMapsFolder.toFile());
                }
                sourceMapsFolder.toFile().mkdirs();
                FileUtils.copyDirectory(context.outputPath().toFile(), sourceMapsFolder.toFile());
            }
        };
    }

    private Path prepareSourcesFolder(Config config, Project project) {
        try {
            Path initialScriptFile = config.getWebappDirectory().resolve(config.getInitialScriptFilename());
            Path destSourcesDir = Files.createDirectories(initialScriptFile.getParent()).resolve(Closure.SOURCES_DIRECTORY_NAME);
            return Files.createDirectories(destSourcesDir).resolve(project.getKey()
                    .replaceAll("\\.","-")
                    .replaceAll(":","-"));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
