package com.vertispan.j2cl.build.provided;

import com.google.auto.service.AutoService;
import com.google.j2cl.common.SourceUtils;
import com.vertispan.j2cl.build.task.*;
import com.vertispan.j2cl.tools.GwtIncompatiblePreprocessor;

import java.nio.file.PathMatcher;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@AutoService(TaskFactory.class)
public class StripSourcesTask extends TaskFactory {
    public static final PathMatcher JAVA_SOURCES = withSuffix(".java");
    public static final PathMatcher NATIVE_JS_SOURCES = withSuffix(".native.js");

    @Override
    public String getOutputType() {
        return OutputTypes.STRIPPED_SOURCES;
    }

    @Override
    public String getTaskName() {
        return "default";
    }

    @Override
    public String getVersion() {
        return "0";
    }

    @Override
    public Task resolve(Project project, Config config) {
        Input inputSources = input(project, OutputTypes.BYTECODE).filter(JAVA_SOURCES);

        return context -> {
            if (inputSources.getFilesAndHashes().isEmpty()) {
                return;// nothing to do
            }
            GwtIncompatiblePreprocessor preprocessor = new GwtIncompatiblePreprocessor(context.outputPath().toFile(), context);
            preprocessor.preprocess(
                    inputSources.getFilesAndHashes().stream()
                            .map(p -> SourceUtils.FileInfo.create(p.getAbsolutePath().toString(), p.getSourcePath().toString()))
                            .collect(Collectors.toUnmodifiableList())
            );
        };
    }
}
