package com.vertispan.j2cl.build;

import io.methvin.watcher.DirectoryChangeEvent;
import io.methvin.watcher.DirectoryChangeListener;
import io.methvin.watcher.DirectoryWatcher;
import io.methvin.watcher.hashing.FileHash;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Given a set of projects and source paths for each, will watch for changes and notify with the new
 * hashes.
 */
public class WatchService {
    private final BuildService buildService;
    private DirectoryWatcher directoryWatcher;

    public WatchService(BuildService buildService) {
        this.buildService = buildService;

    }

    public void watch(Map<Project, List<Path>> sourcePathsToWatch) throws IOException {
        directoryWatcher.watch();
        directoryWatcher = DirectoryWatcher.builder()
                .paths(sourcePathsToWatch.values().stream().flatMap(List::stream).collect(Collectors.toList()))
                .listener(new DirectoryChangeListener() {
                    @Override
                    public void onEvent(DirectoryChangeEvent event) throws IOException {
                        if (!event.isDirectory()) {
                            update(event.rootPath(), event.rootPath().relativize(event.path()), event.eventType(), event.hash());
                        }
                    }
                })
                .build();
    }

    private void update(Path rootPath, Path relativeFilePath, DirectoryChangeEvent.EventType eventType, FileHash hash) {
        buildService.triggerChanges();
    }

    public void close() throws IOException {
        directoryWatcher.close();
    }
}
