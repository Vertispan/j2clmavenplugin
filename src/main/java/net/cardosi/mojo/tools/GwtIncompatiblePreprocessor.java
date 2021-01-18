package net.cardosi.mojo.tools;

import com.google.common.collect.ImmutableMap;
import com.google.j2cl.common.SourceUtils.FileInfo;
import com.google.j2cl.common.Problems;
import com.google.j2cl.tools.gwtincompatible.GwtIncompatibleStripper;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;

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

    public List<FileInfo> preprocess(List<FileInfo> unprocessedFiles) throws IOException {
        Problems problems = new Problems();

        List<FileInfo> result = new ArrayList<>();
        File processed = File.createTempFile("preprocessed", ".jar");
        try (FileSystem out = initZipOutput(processed.getAbsolutePath(), problems)) {

            GwtIncompatibleStripper.preprocessFiles(unprocessedFiles, out.getPath("/"), problems);

            if (problems.hasErrors()) {
                throw new IllegalStateException(problems.getErrors().toString());
            }

            // TODO when the preprocessor doesn't require writing to a zip, we can
            //      change this to just write to the output dir directly
            Path path = out.getPath("/");
            Files.walkFileTree(path, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    Path relativePathInZip = path.relativize(file);
                    Path targetPath = Paths.get(outputDirectory.toURI()).resolve(relativePathInZip.toString());

                    Files.createDirectories(targetPath.getParent());
                    Files.copy(file, targetPath);
                    result.add(FileInfo.create(targetPath.toString(), targetPath.toString()));

                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (Throwable t) {
            problems.getErrors().forEach(System.out::println);
            throw t;
        } finally {
            processed.delete();
        }
        return result;
    }

    // copied from com.google.j2cl.tools.gwtincompatible.GwtIncompatibleStripper, since it is no longer part of SourceUtils
    private static FileSystem initZipOutput(String output, Problems problems) {
        Path outputPath = Paths.get(output);
        if (Files.isDirectory(outputPath)) {
            problems.fatal(Problems.FatalError.OUTPUT_LOCATION, outputPath);
        }

        try {
            // Ensures that we will not fail if the zip already exists.
            Files.delete(outputPath);
            if (!Files.exists(outputPath.getParent())) {
                Files.createDirectories(outputPath.getParent());
            }

            return FileSystems.newFileSystem(
                    URI.create("jar:" + outputPath.toAbsolutePath().toUri()),
                    ImmutableMap.of("create", "true"));
        } catch (IOException e) {
            problems.fatal(Problems.FatalError.CANNOT_CREATE_ZIP, outputPath, e.getMessage());
            return null;
        }
    }
}
