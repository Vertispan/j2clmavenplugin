package com.vertispan.j2cl.build;

import java.nio.file.Path;
import java.util.function.Predicate;

import com.vertispan.j2cl.build.task.CachedPath;
import io.methvin.watcher.hashing.FileHash;

public class ChangedAcceptor implements Predicate<CachedPath> {
    private BuildMap buildMap;

    public ChangedAcceptor(Project project, BuildService buildService) {
        buildMap = buildService != null ? buildService.getBuildMaps().get(project) : null;
    }

    @Override public boolean test(CachedPath cachedPath) {
        boolean found = true;
        if (buildMap != null) {
            found = cachedPath.getHash().equals(FileHash.DIRECTORY) ||
                    buildMap.getChangedFiles().contains(cachedPath.getSourcePath().toString());
        }

        return found;
    }

}
