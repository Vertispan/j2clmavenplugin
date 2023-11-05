package com.vertispan.j2cl.tools;

import com.google.common.collect.ImmutableList;
import com.google.j2cl.common.OutputUtils;
import com.google.j2cl.common.SourceUtils;
import com.google.j2cl.common.Problems;
import com.google.j2cl.transpiler.backend.Backend;
import com.google.j2cl.transpiler.frontend.Frontend;
import com.google.j2cl.transpiler.J2clTranspiler;
import com.google.j2cl.transpiler.J2clTranspilerOptions;
import com.vertispan.j2cl.build.task.BuildLog;

import javax.annotation.Nonnull;
import java.io.File;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

//TODO factor out the wiring to set this up for reuse
public class J2cl {

    private final J2clTranspilerOptions.Builder optionsBuilder;
    private final File jsOutDir;
    private final BuildLog log;

    public J2cl(List<File> strippedClasspath, @Nonnull File bootstrap, File jsOutDir, BuildLog log) {
        this.jsOutDir = jsOutDir;
        this.log = log;
        optionsBuilder = J2clTranspilerOptions.newBuilder()
                .setFrontend(Frontend.JDT)
                .setBackend(Backend.CLOSURE)
                .setClasspaths(Stream.concat(Stream.of(bootstrap), strippedClasspath.stream())
                        .map(File::getAbsolutePath)
                        .collect(Collectors.toUnmodifiableList())
                )
                .setEmitReadableLibraryInfo(false)
                .setEmitReadableSourceMap(false)
                .setGenerateKytheIndexingMetadata(false);
    }

    public boolean transpile(List<SourceUtils.FileInfo> sourcesToCompile, List<SourceUtils.FileInfo> nativeSources) {
        Problems problems = new Problems();
        try (OutputUtils.Output output = OutputUtils.initOutput(jsOutDir.toPath(), problems)) {
            J2clTranspilerOptions options = optionsBuilder
                    .setOutput(output)
                    .setSources(sourcesToCompile)
                    .setNativeSources(nativeSources)
                    .setKotlincOptions(ImmutableList.of())
                    .setWasmEntryPointStrings(ImmutableList.of())
                    .build(problems);

            log.debug(options.toString());

            J2clTranspiler.transpile(options, problems);
        } catch (Problems.Exit e) {
            // Program aborted due to errors recorded in problems, will be logged below
        }

        if (problems.hasErrors() || problems.hasWarnings()) {
            problems.getWarnings().forEach(log::warn);
            problems.getErrors().forEach(log::error);
        } else {
            problems.getInfoMessages().forEach(log::info);
        }
        return !problems.hasErrors();
    }
}
