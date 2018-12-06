package net.cardosi.mojo.tools;

import java.io.File;
import java.io.IOException;
import java.nio.file.FileSystem;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;

import com.google.j2cl.common.Problems;
import com.google.j2cl.frontend.FrontendUtils;
import com.google.j2cl.frontend.FrontendUtils.FileInfo;
import com.google.j2cl.tools.gwtincompatible.JavaPreprocessor;

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
        File processed = File.createTempFile("preprocessed", ".srcjar");
        try (FileSystem out = FrontendUtils.initZipOutput(processed.getAbsolutePath(), new Problems())) {

            JavaPreprocessor.preprocessFiles(unprocessedFiles, out.getPath("/"), problems);

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
            
        } finally {
            processed.delete();
        }
        return result;
    }
}
