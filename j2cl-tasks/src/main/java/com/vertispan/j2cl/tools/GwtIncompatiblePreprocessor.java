package com.vertispan.j2cl.tools;

import com.google.j2cl.common.OutputUtils;
import com.google.j2cl.common.Problems;
import com.google.j2cl.common.SourceUtils.FileInfo;
import com.google.j2cl.tools.gwtincompatible.GwtIncompatibleStripper;
import com.vertispan.j2cl.build.task.BuildLog;

import java.io.File;
import java.util.List;

/**
 * Takes a directory of sources, and removes any types or members that are
 * annotated with @GwtIncompatible
 */
public class GwtIncompatiblePreprocessor {
    private final File outputDirectory;
    private final BuildLog log;

    public GwtIncompatiblePreprocessor(File outputDirectory, BuildLog log) {
        this.outputDirectory = outputDirectory;
        this.log = log;
        if (!outputDirectory.exists() || !outputDirectory.isDirectory()) {
            throw new IllegalArgumentException(outputDirectory.toString());
        }
    }

    public void preprocess(List<FileInfo> unprocessedFiles) {
        Problems problems = new Problems();

        try (OutputUtils.Output output = OutputUtils.initOutput(outputDirectory.toPath(), problems)) {
            GwtIncompatibleStripper.preprocessFiles(unprocessedFiles, output, problems, "GwtIncompatible");

            if (problems.hasErrors()) {
                throw new IllegalStateException(problems.getErrors().toString());
            }
        } catch (Throwable t) {
            problems.getErrors().forEach(log::error);
            throw t;
        }
    }
}
