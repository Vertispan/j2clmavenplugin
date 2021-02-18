package com.vertispan.j2cl.build.provided;

import com.google.auto.service.AutoService;
import com.google.javascript.jscomp.CompilationLevel;
import com.google.javascript.jscomp.DependencyOptions;
import com.vertispan.j2cl.build.*;
import net.cardosi.mojo.tools.Closure;
import org.apache.commons.io.FileUtils;

import java.io.File;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;

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
    public Task resolve(Project project, PropertyTrackingConfig config) {
        // TODO filter to just JS and sourcemaps? probably not required unless we also get sources
        //      from the actual input source instead of copying it along each step
        Input js = input(project, OutputTypes.TRANSPILED_JS);

        return outputPath -> {
            Closure closureCompiler = new Closure();

            assert Files.isDirectory(outputPath);
            File closureOutputDir = outputPath.toFile();

            Path transpiledJsSources = js.getPath();

            // even though we're already making the file in our own hash dir, we also want to
            // name the file by a hash so it has a unique filename based on its contents
            String hash ="TODO_HASH";// js.hash();//TODO consider factoring in the rest of this task's hash
            String outputFile = closureOutputDir + "/" + project.getKey() + hash;

            // if there are no js sources, write an empty file and exit
            try (DirectoryStream<Path> dirStream = Files.newDirectoryStream(transpiledJsSources)) {
                if (!dirStream.iterator().hasNext()) {
                    Files.createFile(Paths.get(outputFile));
                    return;
                }
            }

            // copy the sources locally so that we can create usable sourcemaps
            File sources = new File(closureOutputDir, "sources");
            FileUtils.copyDirectory(transpiledJsSources.toFile(), sources);

            // create the JS bundle, only ordering these files
            boolean success = closureCompiler.compile(
                    CompilationLevel.BUNDLE,
                    DependencyOptions.DependencyMode.SORT_ONLY,
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
        };
    }
}
