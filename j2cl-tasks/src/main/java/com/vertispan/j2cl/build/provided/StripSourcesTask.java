package com.vertispan.j2cl.build.provided;

import com.google.auto.service.AutoService;
import com.google.j2cl.common.SourceUtils;
import com.vertispan.j2cl.build.task.*;
import com.vertispan.j2cl.tools.GwtIncompatiblePreprocessor;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@AutoService(TaskFactory.class)
public class StripSourcesTask extends TaskFactory {
    public static final PathMatcher JAVA_SOURCES = withSuffix(".java");

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
            List<SourceUtils.FileInfo> filesToProcess = new ArrayList<>();
            if (context.lastSuccessfulOutput().isPresent()) {
                Map<Path, CachedPath> unmodified = inputSources.getFilesAndHashes().stream().collect(Collectors.toMap(
                        CachedPath::getSourcePath,
                        Function.identity()
                ));
                //only process changed files, copy unchanged ones
                for (ChangedCachedPath change : inputSources.getChanges()) {
                    // remove the file, since it was changed in some way
                    unmodified.remove(change.getSourcePath());

                    if (change.changeType() != ChangedCachedPath.ChangeType.REMOVED) {
                        // track the files we actually need to process
                        filesToProcess.add(makeFileInfo(change));
                    }
                }
                for (CachedPath path : unmodified.values()) {
                    Files.createDirectories(context.outputPath().resolve(path.getSourcePath()).getParent());
                    Files.copy(context.lastSuccessfulOutput().get().resolve(path.getSourcePath()), context.outputPath().resolve(path.getSourcePath()));
                }
            } else {
                for (CachedPath path : inputSources.getFilesAndHashes()) {
                    filesToProcess.add(makeFileInfo(path));
                }
            }
            GwtIncompatiblePreprocessor preprocessor = new GwtIncompatiblePreprocessor(context.outputPath().toFile(), context);
            preprocessor.preprocess(filesToProcess);
        };
    }

    private SourceUtils.FileInfo makeFileInfo(ChangedCachedPath change) {
        assert change.getNewAbsolutePath().isPresent() : "Can't make a FileInfo if it no longer exists";
        return SourceUtils.FileInfo.create(change.getNewAbsolutePath().get().toString(), change.getSourcePath().toString());
    }

    private SourceUtils.FileInfo makeFileInfo(CachedPath path) {
        return SourceUtils.FileInfo.create(path.getAbsolutePath().toString(), path.getSourcePath().toString());
    }
}
