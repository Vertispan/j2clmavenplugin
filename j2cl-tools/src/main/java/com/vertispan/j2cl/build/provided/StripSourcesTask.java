package com.vertispan.j2cl.build.provided;

import com.google.auto.service.AutoService;
import com.google.j2cl.common.SourceUtils;
import com.vertispan.j2cl.build.task.*;
import net.cardosi.mojo.tools.GwtIncompatiblePreprocessor;

import java.nio.file.FileSystems;
import java.nio.file.PathMatcher;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@AutoService(TaskFactory.class)
public class StripSourcesTask extends TaskFactory {
    public static final PathMatcher JAVA_SOURCES = FileSystems.getDefault().getPathMatcher("glob:**/*.java");

    @Override
    public String getOutputType() {
        return OutputTypes.STRIPPED_SOURCES;
    }

    @Override
    public String getTaskName() {
        return "default";
    }

    @Override
    public Task resolve(Project project, Config config) {
        Input inputSources = input(project, OutputTypes.INPUT_SOURCES).filter(JAVA_SOURCES);
        Input generatedSources = input(project, OutputTypes.GENERATED_SOURCES).filter(JAVA_SOURCES);

        return outputPath -> {
            if (inputSources.getFilesAndHashes().isEmpty()) {
                return;// nothing to do
            }
            GwtIncompatiblePreprocessor preprocessor = new GwtIncompatiblePreprocessor(outputPath.toFile());
            preprocessor.preprocess(
                    Stream.concat(
                            inputSources.getFilesAndHashes().keySet().stream()
                                    .map(p -> SourceUtils.FileInfo.create(inputSources.getPath().toAbsolutePath().resolve(p).toString(), p.toString())),
                            generatedSources.getFilesAndHashes().keySet().stream()
                                    .map(p -> SourceUtils.FileInfo.create(inputSources.getPath().toAbsolutePath().resolve(p).toString(), p.toString()))

                    ).collect(Collectors.toList()
                    )
            );
        };
    }
}
