package com.vertispan.j2cl.build;

import com.vertispan.j2cl.build.task.BuildLog;
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
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Given a set of projects and source paths for each, will watch for changes and notify with the new
 * hashes. If used, will compute the file hashes on its own, no need to ask the build service
 * to do it.
 */
public class WatchService {
    private final BuildQueue buildQueue;
    private final BuildService buildService;
    private final ScheduledExecutorService executorService;
    private final BuildLog buildLog;
    private DirectoryWatcher directoryWatcher;

    public WatchService(BuildService buildService, ScheduledExecutorService executorService, BuildLog log) {
        this.buildQueue = new BuildQueue(buildService);
        this.buildService = buildService;
        this.executorService = executorService;
        this.buildLog =log;
    }

    public void watch(Map<Project, List<Path>> sourcePathsToWatch) throws IOException {
        buildLog.info("Start watching " + sourcePathsToWatch);
        Map<Path, Project> pathToProjects = new HashMap<>();
        sourcePathsToWatch.forEach((project, paths) -> {
            paths.forEach(path -> pathToProjects.put(path, project));
        });
        directoryWatcher = DirectoryWatcher.builder()
                .paths(sourcePathsToWatch.values().stream().flatMap(List::stream).collect(Collectors.toUnmodifiableList()))
                .listener(event -> {
                    if (!event.isDirectory()) {
                        Path rootPath = event.rootPath();
                        update(pathToProjects.get(rootPath), rootPath, rootPath.relativize(event.path()), event.eventType(), event.hash());
                    }
                })
                .build();

        // initial hashes are ready, notify builder of initial hashes since we have them
        for (Map.Entry<Path, Project> entry : pathToProjects.entrySet()) {
            Project project = entry.getValue();
            Path rootPath = entry.getKey();
            Map<Path, DiskCache.CacheEntry> projectFiles = directoryWatcher.pathHashes().entrySet().stream()
                    .filter(e -> e.getValue() != FileHash.DIRECTORY)
                    .filter(e -> e.getKey().startsWith(rootPath))
                    .map(e -> new DiskCache.CacheEntry(rootPath.relativize(e.getKey()), rootPath, e.getValue()))
                    .collect(Collectors.toMap(e -> e.getSourcePath(), Function.identity()));
            buildService.triggerChanges(project, projectFiles, Collections.emptyMap(), Collections.emptySet());
        }

        // start the first build
        buildQueue.requestBuild();

        // start watching to observe changes
        directoryWatcher.watchAsync(executorService);
    }

    private void update(Project project, Path rootPath, Path relativeFilePath, DirectoryChangeEvent.EventType eventType, FileHash hash) {
        switch (eventType) {
            case CREATE:
                buildService.triggerChanges(project, Collections.singletonMap(relativeFilePath, new DiskCache.CacheEntry(relativeFilePath, rootPath, hash)), Collections.emptyMap(), Collections.emptySet());
                break;
            case MODIFY:
                buildService.triggerChanges(project, Collections.emptyMap(), Collections.singletonMap(relativeFilePath, new DiskCache.CacheEntry(relativeFilePath, rootPath, hash)), Collections.emptySet());
                break;
            case DELETE:
                buildService.triggerChanges(project, Collections.emptyMap(), Collections.emptyMap(), Collections.singleton(relativeFilePath));
                break;
            case OVERFLOW:
                //TODO rescan?
                break;
        }

        // wait a moment then start a build (this should be pluggable)
        buildQueue.requestBuild();
    }

    enum BuildState { IDLE, BUILDING, CANCELING_FOR_NEW_BUILD }
    class BuildQueue implements BuildListener {
        private final BuildService buildService;

        private final AtomicBoolean timerStarted = new AtomicBoolean(false);
        private final AtomicReference<BuildState> buildState = new AtomicReference<>(BuildState.IDLE);

        private final AtomicReference<Cancelable> previous = new AtomicReference<>(null);

        BuildQueue(BuildService buildService) {
            this.buildService = buildService;
        }

        public void requestBuild() {
            if (timerStarted.compareAndSet(false, true)) {
                executorService.schedule(this::timerElapsed, 100, TimeUnit.MILLISECONDS);
            } // otherwise already started, will elapse soon
        }

        private void timerElapsed() {
            // if success is false, timerElapsed already ran before we got to it!
            boolean success = timerStarted.compareAndSet(true, false);
            assert success;

            // there can technically be a race here where a new timer starts even though we have already
            // started a build with the specified change - we are okay with this, it is unlikely to occur
            // and the cache will deal with it.

            // atomically update the build state, then see what we should do
            BuildState nextState = this.buildState.updateAndGet(current -> {
                switch (current) {
                    case IDLE:
                        return BuildState.BUILDING;
                    case BUILDING:
                    case CANCELING_FOR_NEW_BUILD:
                        return BuildState.CANCELING_FOR_NEW_BUILD;
                }
                throw new IllegalStateException("Unsupported build state " + current);
            });
            switch (nextState) {
                case BUILDING:
                    startBuild();
                    break;
                case CANCELING_FOR_NEW_BUILD:
                    // trigger cancel - when it has stopped, we will run the new build
                    cancelBuild();
                    break;
                default:
                case IDLE:
                    throw new IllegalStateException("Not possible to be in state " + nextState);
            }
        }

        private void cancelBuild() {
            previous.get().cancel();
        }

        private void startBuild() {
            Cancelable old = null;
            try {
                old = previous.getAndSet(buildService.requestBuild(this));
            } catch (InterruptedException e) {
                // cancelation is always handled already, this cannot happen
                throw new IllegalStateException("Already should have canceled and waited, this shouldn't be possible", e);
            }
            assert old == null : "Must have been null, otherwise there could be another build running";
        }

        @Override
        public void onSuccess() {
            finishBuild();
            if (buildState.get() == BuildState.IDLE) {
                buildLog.info("-----  Build Complete: ready for browser refresh  -----");
            }
        }

        @Override
        public void onFailure() {
            finishBuild();
        }

        private void finishBuild() {
            Cancelable old = previous.getAndSet(null);
            assert old != null : "Must not have been null";
            BuildState nextState = this.buildState.updateAndGet(current -> {
                switch (current) {
                    case BUILDING:
                        return BuildState.IDLE;
                    case CANCELING_FOR_NEW_BUILD:
                        return BuildState.BUILDING;
                    case IDLE:
                    default:
                        throw new IllegalStateException("Can't be in state " + current + " after finishing a build");
                }
            });
            switch (nextState) {
                case IDLE:
                    // do nothing, wait for another update
                    return;
                case BUILDING:
                    startBuild();

                case CANCELING_FOR_NEW_BUILD:
                default:
                    throw new IllegalStateException("Not possible to be in state" + nextState);
            }
        }

        @Override
        public void onError(Throwable throwable) {
            //TODO should shut down, this isn't recoverable
            throwable.printStackTrace();
            System.exit(1);
        }
    }

    public void close() throws IOException {
        directoryWatcher.close();
    }
}
