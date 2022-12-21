package com.vertispan.j2cl.build;

import com.vertispan.j2cl.build.task.CachedPath;
import com.vertispan.j2cl.build.task.Input;

import java.nio.file.Path;
import java.util.Optional;

public class ChangedCachedPath implements Input.ChangedCachedPath {
    private final ChangeType type;
    private final Path sourcePath;
    private final Optional<CachedPath> newIfAny;

    public ChangedCachedPath(ChangeType type, Path sourcePath, CachedPath newPath) {
        this.type = type;
        this.sourcePath = sourcePath;
        this.newIfAny = Optional.ofNullable(newPath);
    }

    @Override
    public ChangeType changeType() {
        return type;
    }

    @Override
    public Path getSourcePath() {
        return sourcePath;
    }

    @Override
    public Optional<Path> getNewAbsolutePath() {
        return newIfAny.map(CachedPath::getAbsolutePath);
    }
}
