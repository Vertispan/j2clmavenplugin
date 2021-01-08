package net.cardosi.mojo.tools;

import com.google.common.collect.ImmutableMap;
import com.google.j2cl.common.J2clUtils;
import com.google.j2cl.common.Problems;
import com.google.common.io.MoreFiles;
import com.google.j2cl.tools.gwtincompatible.GwtIncompatibleStripper;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
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

        //System.out.println("strip sourceDir" + sourceDir);
        for (Path file : unprocessedFiles) {
            Path localPath = sourceDir.toPath().relativize(file);
            final Path targetPath = outputDirectory.toPath().resolve(localPath);
            Files.createDirectories(targetPath.getParent());
            Files.deleteIfExists(targetPath);
            if (file.toString().endsWith(".java")) {
                String fileContent = MoreFiles.asCharSource(file, UTF_8).read();
                String processedFileContent = strip(fileContent);
                // Write the processed file to output
                //System.out.println("strip write" + targetPath + ":" + file);
                Path writePath = Files.write(targetPath, Collections.singleton(processedFileContent), StandardCharsets.UTF_8);
                //System.out.println("strip writePath: " + writePath);
                //targetPath.toFile().setLastModified(file.toFile().lastModified()); // last modified must be same, for calculated ChangeSet
                if (problems.hasErrors()) {
                    System.out.println("strip error: " + problems.getErrors().toString());
                    throw new IOException(problems.getErrors().toString());
                }
            } else {
                Files.copy(file, targetPath);
                //System.out.println("strip copy: " + file + ":" + targetPath);
                //targetPath.toFile().setLastModified(file.toFile().lastModified()); // last modified must be same, for calculated ChangeSet
            }
        }
    }
}