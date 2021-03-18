package com.vertispan.j2cl.build;

import com.vertispan.j2cl.build.impl.CollectedTaskInputs;
import io.methvin.watcher.DirectoryChangeEvent;
import io.methvin.watcher.DirectoryChangeListener;
import io.methvin.watcher.DirectoryWatcher;
import io.methvin.watcher.hashing.FileHash;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Given a set of projects and source paths for each, will watch for changes and notify with the new
 * hashes. If used, will compute the file hashes on its own, no need to ask the build service
 * to do it.
 */
public class WatchService {
    private final BuildService buildService;
    private final ScheduledExecutorService executorService;
    private DirectoryWatcher directoryWatcher;

    public WatchService(BuildService buildService, ScheduledExecutorService executorService) {
        this.buildService = buildService;
        this.executorService = executorService;
    }

    public void watch(Map<Project, List<Path>> sourcePathsToWatch) throws IOException {
        Map<Path, Project> pathToProjects = new HashMap<>();
        sourcePathsToWatch.forEach((project, paths) -> {
            paths.forEach(path -> pathToProjects.put(path, project));
        });
        directoryWatcher = DirectoryWatcher.builder()
                .paths(sourcePathsToWatch.values().stream().flatMap(List::stream).collect(Collectors.toList()))
                .listener(new DirectoryChangeListener() {
                    @Override
                    public void onEvent(DirectoryChangeEvent event) throws IOException {
                        if (!event.isDirectory()) {
                            Path rootPath = event.rootPath();
                            update(pathToProjects.get(rootPath), rootPath.relativize(event.path()), event.eventType(), event.hash());
                        }
                    }
                })
                .build();

        // initial hashes are ready, notify builder of initial hashes since we have them
        for (Map.Entry<Path, Project> entry : pathToProjects.entrySet()) {
            Project project = entry.getValue();
            Path rootPath = entry.getKey();
            Map<Path, FileHash> projectFiles = directoryWatcher.pathHashes().entrySet().stream()
                    .filter(e -> e.getKey().startsWith(rootPath))
                    .collect(Collectors.toMap(e -> rootPath.relativize(e.getKey()), Map.Entry::getValue));
            buildService.triggerChanges(project, projectFiles, Collections.emptyMap(), Collections.emptySet());
        }

        // start watching to observe changes
        directoryWatcher.watchAsync(executorService);
    }

    private void update(Project project, Path relativeFilePath, DirectoryChangeEvent.EventType eventType, FileHash hash) {
        switch (eventType) {
            case CREATE:
                buildService.triggerChanges(project, Collections.singletonMap(relativeFilePath, hash), Collections.emptyMap(), Collections.emptySet());
                break;
            case MODIFY:
                buildService.triggerChanges(project, Collections.emptyMap(), Collections.singletonMap(relativeFilePath, hash), Collections.emptySet());
                break;
            case DELETE:
                buildService.triggerChanges(project, Collections.emptyMap(), Collections.emptyMap(), Collections.singleton(relativeFilePath));
                break;
            case OVERFLOW:
                //TODO rescan?
                break;
        }

        // wait a moment then start a build (this should be pluggable)
        //TODO need to debounce this call so we only ever have one at a time pending
        //TODO if requestBuild() is ever blocking (so that the lock is held), we can't enqueue to the same executor as task work is done on
        executorService.schedule(buildService::requestBuild, 100, TimeUnit.MILLISECONDS);
    }

    public void close() throws IOException {
        directoryWatcher.close();
    }
}
