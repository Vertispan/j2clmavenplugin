package net.cardosi.mojo.tools;

import com.google.j2cl.common.J2clUtils;
import com.google.j2cl.common.Problems;
import com.google.common.io.MoreFiles;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Collections;
import java.util.List;

import static com.google.j2cl.tools.gwtincompatible.GwtIncompatibleStripper.strip;
import static java.nio.charset.StandardCharsets.UTF_8;

/**
 * Takes a directory of sources, and removes any types or members that are
 * annotated with @GwtIncompatible
 */
public class GwtIncompatiblePreprocessor {
    private final File outputDirectory;

    public GwtIncompatiblePreprocessor(File outputDirectory) {
        this.outputDirectory = outputDirectory;
        if (!outputDirectory.exists() || !outputDirectory.isDirectory()) {
            throw new IllegalArgumentException(outputDirectory.toString());
        }
    }

    public void preprocess(File sourceDir, List<Path> unprocessedFiles) throws IOException {
        Problems problems = new Problems();

        for (Path file : unprocessedFiles) {
            Path localPath = sourceDir.toPath().relativize(file);
            final Path targetPath = outputDirectory.toPath().resolve(localPath);
            Files.createDirectories(targetPath.getParent());
            Files.deleteIfExists(targetPath);
            if (file.endsWith(".java")) {
                String fileContent = MoreFiles.asCharSource(file, UTF_8).read();
                // Write the processed file to output
                //J2clUtils.writeToFile(targetPath, strip(fileContent), problems);
                Files.write(targetPath, Collections.singleton(fileContent), StandardCharsets.UTF_8);
                //targetPath.toFile().setLastModified(file.toFile().lastModified()); // last modified must be same, for calculated ChangeSet
                if (problems.hasErrors()) {
                    throw new IOException(problems.getErrors().toString());
                }
            } else {
                Files.copy(file, targetPath);
                //targetPath.toFile().setLastModified(file.toFile().lastModified()); // last modified must be same, for calculated ChangeSet
            }
        }
    }
}
