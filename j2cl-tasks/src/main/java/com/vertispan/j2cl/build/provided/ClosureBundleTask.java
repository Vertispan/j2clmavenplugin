package com.vertispan.j2cl.build.provided;

import com.google.auto.service.AutoService;
import com.google.javascript.jscomp.CompilationLevel;
import com.google.javascript.jscomp.CompilerOptions;
import com.google.javascript.jscomp.DependencyOptions;
import com.vertispan.j2cl.build.task.*;
import io.methvin.watcher.hashing.Murmur3F;
import com.vertispan.j2cl.tools.Closure;
import org.apache.commons.io.FileUtils;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Emits a single compilationLevel=BUNDLE for this project only, without any dependencies.
 */
@AutoService(TaskFactory.class)
public class ClosureBundleTask extends TaskFactory {

    public static final String BUNDLE_JS_EXTENSION = ".bundle.js";

    @Override
    public String getOutputType() {
        return OutputTypes.BUNDLED_JS;
    }

    @Override
    public String getTaskName() {
        return "default";
    }

    @Override
    public String getVersion() {
        return "1";
    }

    @Override
    public Task resolve(Project project, Config config) {
        final List<Input> js;
        if (project.isJsZip()) {
            js = Collections.singletonList(input(project, OutputTypes.BYTECODE).filter(ClosureTask.PLAIN_JS_SOURCES));
        } else {
            // TODO filter to just JS and sourcemaps? probably not required unless we also get sources
            //      from the actual input source instead of copying it along each step
            js = Stream.of(
                            input(project, OutputTypes.TRANSPILED_JS),
                            input(project, OutputTypes.BYTECODE)
                    )
                    .map(i -> i.filter(ClosureTask.PLAIN_JS_SOURCES))
                    .collect(Collectors.toList());
        }

        return context -> {
            assert Files.isDirectory(context.outputPath());
            File closureOutputDir = context.outputPath().toFile();

            // even though we're already making the file in our own hash dir, we also want to
            // name the file by a hash so it has a unique filename based on its contents
            String fileNameKey = project.getKey().replaceAll("[^\\-_a-zA-Z0-9.]", "-");
            String outputFile = closureOutputDir + "/" + fileNameKey + ".js";

            Path outputFilePath = Paths.get(outputFile);
            if (!js.stream().map(Input::getFilesAndHashes).flatMap(Collection::stream).findAny().isPresent()) {
                // if there are no js sources, write an empty file and exit
                Files.createFile(outputFilePath);
                return;// nothing to do
            }

            Closure closureCompiler = new Closure(context);

            // copy the sources locally so that we can create usable sourcemaps
            //TODO consider a soft link
            File sources = new File(closureOutputDir, Closure.SOURCES_DIRECTORY_NAME);
            for (Path path : js.stream().map(Input::getParentPaths).flatMap(Collection::stream).collect(Collectors.toList())) {
                FileUtils.copyDirectory(path.toFile(), sources);
            }

            // create the JS bundle, only ordering these files
            boolean success = closureCompiler.compile(
                    CompilationLevel.BUNDLE,
                    DependencyOptions.DependencyMode.SORT_ONLY,
                    CompilerOptions.LanguageMode.NO_TRANSPILE,
                    Collections.singletonMap(
                            sources.getAbsolutePath(),
                            js.stream()
                                    .map(Input::getFilesAndHashes)
                                    .flatMap(Collection::stream)
                                    .map(CachedPath::getSourcePath)
                                    .map(Path::toString)
                                    .collect(Collectors.toList())
                    ),
                    sources,
                    Collections.emptyList(),
                    Collections.emptyMap(),
                    Collections.emptyList(),//TODO actually pass these in when we can restrict and cache them sanely
                    Optional.empty(),
                    true,//TODO have this be passed in,
                    true,//default to true, will have no effect anyway
                    false,
                    false,
                    "CUSTOM", // doesn't matter, bundle won't check this
                    outputFile
            );

            if (!success) {
                throw new IllegalStateException("Closure Compiler failed, check log for details");
            }

            // hash the file itself, rename to include that hash
            Murmur3F murmur = new Murmur3F();
            try (InputStream is = new BufferedInputStream(Files.newInputStream(outputFilePath))) {
                int b;
                while ((b = is.read()) != -1) {
                    murmur.update(b);
                }
            }
            Files.move(outputFilePath, outputFilePath.resolveSibling(fileNameKey + "-" + murmur.getValueHexString() + BUNDLE_JS_EXTENSION));
            //TODO when back to keyboard rename sourcemap? is that a thing we need to do?
        };
    }
}
