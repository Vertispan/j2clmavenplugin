package com.vertispan.j2cl.build.provided;

import com.google.auto.service.AutoService;
import com.google.j2cl.common.SourceUtils;
import com.vertispan.j2cl.build.task.*;
import com.vertispan.j2cl.tools.J2cl;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.Collection;
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
        List<Input> classpathHeaders = scope(project.getDependencies(), com.vertispan.j2cl.build.task.Dependency.Scope.COMPILE)
                .stream()
                .map(inputs(OutputTypes.STRIPPED_BYTECODE_HEADERS))
                // we only want bytecode _changes_, but we'll use the whole dir
                .map(input -> input.filter(JAVA_BYTECODE))
                .collect(Collectors.toUnmodifiableList());

        Input ownStrippedBytecode = null;
        //TODO api question, do we not allow incremental builds of external deps?
        boolean incrementalEnabled = config.isIncrementalEnabled();
        if (incrementalEnabled && project.hasSourcesMapped()) {
            ownStrippedBytecode = input(project, OutputTypes.STRIPPED_BYTECODE_HEADERS).filter(JAVA_BYTECODE);
        }

        File bootstrapClasspath = config.getBootstrapClasspath();
        List<File> extraClasspath = config.getExtraClasspath();
        return context -> {
            if (ownJavaSources.getFilesAndHashes().isEmpty()) {
                return;// nothing to do
            }

            nope:
            if (false && context.lastSuccessfulOutput().isPresent()) {
                if (!incrementalEnabled || !project.hasSourcesMapped()) {
                    break nope;
                }
                // maybe attempt incremental...
                Path previousBuildData = context.lastSuccessfulOutput().get().resolve("build.data");

                for (Input classpathHeader : classpathHeaders) {
                    if (!classpathHeader.getChanges().isEmpty()) {
                        break nope;
                    }
                }

                //... do things, copying old JS that still matches current inputs, don't copy files that match deleted inputs,
                //    recompile files that match changed inputs or have changed dependencies

                // For all files that actually exist today, try to copy their old output to our new output path
                // This will get all existing output files (note that we might still rebuild them), and skip "removed" files
                // (since they aren't in the current list of getFilesAndHashes(), but "added" files will result in an error
                for (CachedPath file : ownJavaSources.getFilesAndHashes()) {
                    for (Path existing : expandToExistingFiles(previousBuildData, file.getSourcePath())) {
                        // TODO make sure it exists before copying - it might be a new file and not exist
                        Files.copy(context.lastSuccessfulOutput().get().resolve(existing), context.outputPath().resolve(existing));
                    }
                }

                // TODO deal with native sources changing

                List<Path> myJavaSourcesThatHaveToBeRebuilt = new ArrayList<>();
                for (Input.ChangedCachedPath change : ownJavaSources.getChanges()) {
                    switch (change.changeType()) {
                        case ADDED:
                            //noop, already didn't exist
                            myJavaSourcesThatHaveToBeRebuilt.add(change.getSourcePath());
                            break;
                        case REMOVED:
                            //noop, wasn't copied
                            //TODO anything that used to (transitively or without changes) depend on this must be rebuilt!
                            myJavaSourcesThatHaveToBeRebuilt.addAll(findFilesThatDependedOnPath(previousBuildData, change.getSourcePath()));
                            break;
                        case MODIFIED:
                            //delete old output for this file since we must rebuild
                            for (Path existing : expandToExistingFiles(previousBuildData, change.getSourcePath())) {
                                Files.delete(existing);
                            }
                            // TODO expand the set of files that were modified because this was modified
                            myJavaSourcesThatHaveToBeRebuilt.add(change.getSourcePath());
                            myJavaSourcesThatHaveToBeRebuilt.addAll(findFilesThatDependedOnPath(previousBuildData, change.getSourcePath()));
                            break;
                    }
                }

                // Run J2CL with
                //  * mySourcesThatHaveToBeRebuilt
                //  * any native sources that applied to those, or were changed
                //  * existing classpath, plus OUR OWN CURRENT SOURCES
                return;
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
        };
    }

    private Collection<Path> findFilesThatDependedOnPath(Path previousBuildData, Path sourcePath) {
        return null;
    }

    private List<Path> expandToExistingFiles(Path previousBuildData, Path file) {
        return null;
    }
}
