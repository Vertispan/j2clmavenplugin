package com.vertispan.j2cl.build.provided;

import com.google.auto.service.AutoService;
import com.google.javascript.jscomp.CompilationLevel;
import com.google.javascript.jscomp.CompilerOptions;
import com.google.javascript.jscomp.DependencyOptions;
import com.vertispan.j2cl.build.task.*;
import io.methvin.watcher.hashing.FileHasher;
import net.cardosi.mojo.tools.Closure;
import org.apache.commons.io.FileUtils;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.stream.Collectors;

/**
 * Emits a single compilationLevel=BUNDLE for this project only, without any dependencies.
 */
@AutoService(TaskFactory.class)
public class ClosureBundleTask extends TaskFactory {
    @Override
    public String getOutputType() {
        return OutputTypes.BUNDLED_JS;
    }

    @Override
    public String getTaskName() {
        return "default";
    }

    @Override
    public Task resolve(Project project, Config config) {
        // TODO filter to just JS and sourcemaps? probably not required unless we also get sources
        //      from the actual input source instead of copying it along each step
        Input js = input(project, OutputTypes.TRANSPILED_JS);

        return output -> {
            assert Files.isDirectory(output.path());
            File closureOutputDir = output.path().toFile();

            // even though we're already making the file in our own hash dir, we also want to
            // name the file by a hash so it has a unique filename based on its contents
            String outputFile = closureOutputDir + "/" + project.getKey() + ".js";

            if (js.getFilesAndHashes().isEmpty()) {
                //TODO we probably need to create an empty file
                return;// nothing to do
            }

            Closure closureCompiler = new Closure();

            // if there are no js sources, write an empty file and exit
            Path outputFilePath = Paths.get(outputFile);
            if (js.getFilesAndHashes().isEmpty()) {
                Files.createFile(outputFilePath);
                return;
            }

            // copy the sources locally so that we can create usable sourcemaps
            //TODO consider a soft link
            //TODO also consider using the PersistentInputStore to let try to cheat and map paths
            File sources = new File(closureOutputDir, "sources");
            for (Path path : js.getParentPaths()) {
                FileUtils.copyDirectory(path.toFile(), sources);
            }

            // create the JS bundle, only ordering these files
            boolean success = closureCompiler.compile(
                    CompilationLevel.BUNDLE,
                    DependencyOptions.DependencyMode.SORT_ONLY,
                    CompilerOptions.LanguageMode.NO_TRANSPILE,
                    js.getFilesAndHashes().stream().map(CachedPath::getAbsolutePath).map(Path::toString).collect(Collectors.toList()),
                    sources,
                    Collections.emptyList(),
                    Collections.emptyList(),
                    Collections.emptyMap(),
                    Collections.emptyList(),//TODO actually pass these in when we can restrict and cache them sanely
                    null,
                    true,//TODO have this be passed in,
                    true,//default to true, will have no effect anyway
                    false,
                    false,
                    outputFile
            );

            if (!success) {
                throw new IllegalStateException("Closure Compiler failed, check log for details");
            }

            // hash the file itself, rename to include that hash
            String fileHash = FileHasher.DEFAULT_FILE_HASHER.hash(outputFilePath).asString();
            Files.move(outputFilePath, outputFilePath.resolveSibling(project.getKey() + "-" + fileHash + ".js"));
            //TODO when back to keyboard rename sourcemap? is that a thing we need to do?
        };
    }
}
