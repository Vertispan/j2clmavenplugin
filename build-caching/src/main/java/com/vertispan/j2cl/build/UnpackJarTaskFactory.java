package com.vertispan.j2cl.build;

import com.vertispan.j2cl.build.task.Config;
import com.vertispan.j2cl.build.task.Project;
import com.vertispan.j2cl.build.task.TaskFactory;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public class UnpackJarTaskFactory extends TaskFactory {
    @Override
    public String getOutputType() {
        return "unpack";
    }

    @Override
    public String getTaskName() {
        return "unpack";
    }

    @Override
    public String getVersion() {
        return "0";
    }

    @Override
    public Task resolve(Project project, Config config) {
        // we don't have any proper inputs or configs

        // given the first (only) entry in the project's sources, unpack them
        return context -> {
            List<String> sourceRoots = ((com.vertispan.j2cl.build.Project) project).getSourceRoots();
            assert sourceRoots.size() == 1;

            //collect sources from jar instead
            try (ZipFile zipInputFile = new ZipFile(sourceRoots.get(0))) {
                for (ZipEntry z : Collections.list(zipInputFile.entries())) {
                    if (z.isDirectory()) {
                        continue;
                    }
                    Path outPath = context.outputPath().resolve(z.getName());
                    try (InputStream inputStream = zipInputFile.getInputStream(z)) {
                        Files.createDirectories(outPath.getParent());
                        Files.copy(inputStream, outPath);
                    }
                }
            }
        };
    }
}
