package com.vertispan.j2cl.build;

import com.vertispan.j2cl.build.task.CachedPath;

import java.nio.file.Path;
import java.util.Optional;

/**
 * Implementation of the ChangedCachedPath interface.
 */
public class ChangedCachedPath implements com.vertispan.j2cl.build.task.ChangedCachedPath {
    private final ChangeType type;
    private final Path sourcePath;
    private final CachedPath newIfAny;

    public ChangedCachedPath(ChangeType type, Path sourcePath, CachedPath newPath) {
        this.type = type;
        this.sourcePath = sourcePath;
        this.newIfAny = newPath;
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
        return Optional.ofNullable(newIfAny).map(CachedPath::getAbsolutePath);
    }
}
