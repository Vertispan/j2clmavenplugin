/*
 * Copyright © 2021 j2cl-maven-plugin authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.vertispan.j2cl.build;

import com.google.gson.GsonBuilder;
import com.vertispan.j2cl.build.impl.CollectedTaskInputs;
import com.vertispan.j2cl.build.task.CachedPath;
import io.methvin.watcher.PathUtils;
import io.methvin.watcher.hashing.FileHash;
import io.methvin.watcher.hashing.FileHasher;
import io.methvin.watcher.hashing.Murmur3F;
import io.methvin.watchservice.MacOSXListeningWatchService;
import io.methvin.watchservice.WatchablePath;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.WatchService;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.stream.Collectors;

/**
 * Manages the cached task inputs and outputs, without direct knowledge of the project or task apis.
 */
public abstract class DiskCache {
    private static final boolean IS_MAC = System.getProperty("os.name").toLowerCase().contains("mac");
    private static final int MARK_ACTIVE_UPDATE_DELAY = Integer.getInteger("j2cl.diskcache.mark_active_update_delay_ms", 1000);
    private static final int MAX_STALE_AGE = Integer.getInteger("j2cl.diskcache.max_stale_age", 10);

    public class CacheResult {
        private final Path taskDir;

        public CacheResult(Path taskDir) {
            this.taskDir = taskDir;
        }

        public Path taskDir() {
            return taskDir;
        }

        public Path logFile() {
            //TODO finish building a logger that will write to this
            return DiskCache.this.logFile(taskDir);
        }
        public Path outputDir() {
            return DiskCache.this.outputDir(taskDir);
        }

        public TaskOutput output() {
            TaskOutput taskOutput = knownOutputs.get(taskDir);
            if (taskOutput == null) {
                throw new IllegalStateException("Output not yet ready for " + taskDir);
            }
            return taskOutput;
        }

        public Path cachedSummary() {
            return DiskCache.this.cacheSummary(taskDir);
        }

        public void markSuccess() {
            markFinished(this);
            runningTasks.remove(taskDir);
        }
        public void markFailure() {
            markFailed(this);
            runningTasks.remove(taskDir);
        }

        public void markBegun() {
            runningTasks.add(taskDir);
        }

        public void cancel() {
            try {
                deleteRecursively(taskDir);
            } catch (IOException e) {
                // log and return, might be useful for debugging, but not recoverable
                e.printStackTrace();
            }
        }
    }

    protected final File cacheDir;
    private final Executor executor;
    /**
     * A single watch service to monitor all changes to the cache dir, under the assumption that
     * the entire cache directory is on a single filesystem.
     */
    private final WatchService service;
    /**
     * A thread to monitor for changes, notify waiting work as necessary.
     */
    private final Thread watchThread = new Thread(this::checkForWork, "DiskCacheThread");
    private Map<Path, TaskOutput> knownOutputs = new ConcurrentHashMap<>();
    private Map<Input, TaskOutput> lastSuccessfulOutputs = new ConcurrentHashMap<>();

    private final Map<Path, Path> knownMarkers = new ConcurrentHashMap<>();
    private final Map<Path, Set<PendingCacheResult>> taskFutures = new ConcurrentHashMap<>();

    private final List<Path> runningTasks = new CopyOnWriteArrayList<>();
    private final Thread livenessThread = new Thread(this::markActive, "DiskCacheLivenessThread");

    public DiskCache(File cacheDir, Executor executor) throws IOException {
        this.cacheDir = cacheDir;
        this.executor = executor;
        cacheDir.mkdirs();
        if (!cacheDir.exists() && !cacheDir.isDirectory()) {
            throw new IllegalArgumentException("Can't use " + cacheDir + ", failed to create it, or already exists and isn't a directory");
        }

        if (IS_MAC) {
            service = new MacOSXListeningWatchService();
        } else {
            service = cacheDir.toPath().getFileSystem().newWatchService();
        }

        watchThread.start();
        livenessThread.start();
    }

    private void checkForWork() {
        try {
            WatchKey key;
            while ((key = service.take()) != null) {
                for (WatchEvent<?> event : key.pollEvents()) {
                    if (event.kind() == StandardWatchEventKinds.ENTRY_CREATE) {
                        // task ended one way or the other
                        Path taskDir = pathFromWatchable(key.watchable());
                        Path createdPath = taskDir.resolve((Path) event.context());
                        Set<PendingCacheResult> listeners = taskFutures.get(taskDir);
                        if (createdPath.equals(successMarker(taskDir))) {
                            try {
                                knownOutputs.put(taskDir, makeOutput(taskDir));
                                listeners.forEach(PendingCacheResult::success);
                            } catch (UncheckedIOException ioException) {
                                // failure to hash is pretty terrible, we're in trouble
                                ioException.printStackTrace();
                                listeners.forEach(l -> l.error(ioException));
                            }
                        } else if (createdPath.equals(failureMarker(taskDir))) {
                            listeners.forEach(PendingCacheResult::failure);
                        } //else this is the log file
                    } else if (event.kind() == StandardWatchEventKinds.ENTRY_DELETE) {
                        // task was canceled, we should attempt to take over
                        Path taskDir = (Path) event.context();
                        Set<PendingCacheResult> listeners = taskFutures.get(taskDir);

                        // Attempt to re-create the directory (note that we might not be the only process watching
                        // for this work to complete), then alert only the first listener to attempt the work again.
                        if (taskDir.toFile().mkdir()) {
                            Files.createDirectory(outputDir(taskDir));
                            Files.createFile(logFile(taskDir));
                            listeners.iterator().next().ready();
                        }
                        listeners.forEach(l -> l.error(new IllegalStateException("Existing task was canceled, not yet supported")));
                    }
                }
                key.reset();
            }
        } catch (InterruptedException e) {
            // asked to shut down, time to stop
            // TODO mark all pending work as canceled?
        } catch (IOException e) {
            // disaster, can't interact with the cache, stop and give up
            // TODO mark all pending work as canceled?
        } catch (ClosedWatchServiceException e) {
            if(!livenessThread.getState().equals(Thread.State.TERMINATED)) {
                 throw new Error(e);
            }

            // This is purely noise in the log and doesn't indicate an actual
            // error, so it can be safely ignored. Please see:
            // https://github.com/Vertispan/j2clmavenplugin/issues/188
        }
    }

    private void markActive() {
        while (true) {
            long startLoop = System.currentTimeMillis();
            FileTime now = FileTime.from(Instant.now());
            runningTasks.forEach(path -> {
                try {
                    Files.setLastModifiedTime(path, now);
                } catch (IOException e) {
                    // race, probably the file was deleted, leaving it in the collection
                    // for now, the failing task will cause the entry to be deleted.
                    e.printStackTrace();
                }
            });
            try {
                long remainingDelay = MARK_ACTIVE_UPDATE_DELAY - (System.currentTimeMillis() - startLoop);
                if (remainingDelay > 0) {
                    Thread.sleep(remainingDelay);
                } else {
                    System.out.println("Negative remaining delay, continuing " + now);
                }
            } catch (InterruptedException e) {
                return;// done
            }
        }
    }

    private Path pathFromWatchable(Watchable watchable) {
        if (watchable instanceof WatchablePath) {
            return ((WatchablePath) watchable).getFile();
        }
        if (watchable instanceof Path) {
            return (Path) watchable;
        }
        throw new UnsupportedOperationException("Can't handle watchable of type " + watchable.getClass());
    }

    private TaskOutput makeOutput(Path taskDir) {
        Path outputDir = outputDir(taskDir);
        return new TaskOutput(hashContents(outputDir));
    }

    public static class CacheEntry implements Comparable<CacheEntry>, CachedPath {
        /** Relative path to the resuls dir or its original source dir */
        private final Path sourcePath;
        /** Absolute path to the results dir. Not to be serialized to disk. */
        private final Path absoluteParent;

        /** Hash of the file, so we can notice changes, or hash the tree.  */
        private final FileHash hash;

        public CacheEntry(Path sourcePath, Path absoluteParent, FileHash hash) {
            if (sourcePath.isAbsolute()) {
                this.sourcePath = absoluteParent.relativize(sourcePath);
            } else {
                this.sourcePath = sourcePath;
            }
            this.absoluteParent = absoluteParent;
            this.hash = hash;
        }

        @Override
        public Path getSourcePath() {
            return sourcePath;
        }

        public Path getAbsoluteParent() {
            return absoluteParent;
        }

        @Override
        public Path getAbsolutePath() {
            return absoluteParent.resolve(sourcePath);
        }

        /**
         * Internal API, as this is not at this time used by any caller.
         */
        public FileHash getHash() {
            return hash;
        }

        @Override
        public int compareTo(CacheEntry cacheEntry) {
            return sourcePath.compareTo(cacheEntry.sourcePath);
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;

            CacheEntry that = (CacheEntry) o;

            if (!sourcePath.equals(that.sourcePath)) return false;
            if (!absoluteParent.equals(that.absoluteParent)) return false;
            return hash.equals(that.hash);
        }

        @Override
        public int hashCode() {
            int result = sourcePath.hashCode();
            result = 31 * result + absoluteParent.hashCode();
            result = 31 * result + hash.hashCode();
            return result;
        }

        @Override
        public String toString() {
            return "CacheEntry{" +
                    "sourcePath=" + sourcePath +
                    ", absoluteParent=" + absoluteParent +
                    ", hash=" + hash +
                    '}';
        }
    }

    /**
     * Helper like PathUtils.initWatcherState to produce the relative paths of any files
     * in a path, and their corresponding hashes.
     */
    public static Collection<CacheEntry> hashContents(Path path) {
        Set<CacheEntry> fileHashes = new HashSet<>();
        if (Files.exists(path)) {
            FileHasher fileHasher = FileHasher.DEFAULT_FILE_HASHER;
            try {
                Files.walkFileTree(
                        path,
                        new SimpleFileVisitor<Path>() {
                            @Override
                            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                                return FileVisitResult.CONTINUE;
                            }

                            @Override
                            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                                if (attrs.isDirectory()) {
                                    // ignore directories
                                    return FileVisitResult.CONTINUE;
                                }
                                FileHash hash = PathUtils.hash(fileHasher, file);
                                if (hash == null) {
                                    //file could have been deleted or was otherwise unreadable
                                    //TODO how do we handle this? For now skipping as PathUtils does
                                } else {
                                    fileHashes.add(new CacheEntry(file, path, hash));
                                }
                                return FileVisitResult.CONTINUE;
                            }
                        });
            } catch (IOException ioException) {
                throw new UncheckedIOException(ioException);
            }
        }
        return fileHashes;
    }

    public void close() throws IOException, InterruptedException {
        livenessThread.interrupt();
        watchThread.interrupt();
        service.close();
        watchThread.join();
        for (Path path : runningTasks) {
            deleteRecursively(path);
        }
    }

    private void deleteRecursively(Path path) throws IOException {
        if (Files.exists(path)) {
            if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
                try (DirectoryStream<Path> entries = Files.newDirectoryStream(path)) {
                    for (Path entry : entries) {
                        deleteRecursively(entry);
                    }
                }
            }
            Files.deleteIfExists(path);
        }
    }

    private String taskSummaryContents(CollectedTaskInputs inputs) {
        TaskSummaryDiskFormat src = new TaskSummaryDiskFormat();
        src.setProjectKey(inputs.getProject().getKey());
        src.setOutputType(inputs.getTaskFactory().getOutputType());
        src.setTaskImpl(inputs.getTaskFactory().getClass().getName());
        src.setTaskImplVersion(inputs.getTaskFactory().getVersion());

        src.setInputs(inputs.getInputs().stream()
                .map(Input::makeDiskFormat)
                .collect(Collectors.groupingBy(i -> i.getProjectKey() + "-" + i.getOutputType()))
                .values().stream()
                .map(list -> {
                    TaskSummaryDiskFormat.InputDiskFormat result = new TaskSummaryDiskFormat.InputDiskFormat();
                    result.setProjectKey(list.get(0).getProjectKey());
                    result.setOutputType(list.get(0).getOutputType());

                    result.setFileHashes(
                            list.stream().flatMap(i -> i.getFileHashes().entrySet().stream())
                                    .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, (left, right) -> {
                                        if (left.equals(right)) {
                                            return left;
                                        }
                                        throw new IllegalStateException("Two hashes for one file! " + left + " vs " + right);
                                    }))
                    );

                    return result;
                })
                .collect(Collectors.toUnmodifiableList()));

        src.setConfigs(inputs.getUsedConfigs());

        return new GsonBuilder().serializeNulls().setPrettyPrinting().create().toJson(src);
    }

    protected abstract Path taskDir(String projectName, String hashString, String outputType);

    protected abstract Path successMarker(Path taskDir);
    protected abstract Path failureMarker(Path taskDir);
    protected abstract Path logFile(Path taskDir);
    protected abstract Path outputDir(Path taskDir);
    protected abstract Path cacheSummary(Path taskDir);

    interface Listener {
        /** Ready for the current listener to do the work */
        void onReady(CacheResult result);
        /** Someone else did it, but failed for some reason, not re-runnable */
        void onFailure(CacheResult result);
        /** Someone else tried to do it, but ran into an error, possibly recoverable if we try again */
        void onError(Throwable throwable);
        /** Someone else finished it, successfully, notify listeners */
        void onSuccess(CacheResult result);
    }
    public class PendingCacheResult implements Cancelable {
        private final Path taskDir;
        private final Listener listener;
        private boolean done;

        public PendingCacheResult(Path taskDir, Listener listener) {
            this.taskDir = taskDir;
            this.listener = listener;
        }

        private synchronized void error(Throwable throwable) {
            if (done) {
                return;
            }
            remove();
            listener.onError(throwable);
        }

        private synchronized void success() {
            if (done) {
                return;
            }
            remove();
            executor.execute(() -> {
                listener.onSuccess(new CacheResult(taskDir));
            });
        }

        private void remove() {
            // mop up so that this won't be called/retained any more
            //TODO this shouldn't be necessary if all the calls to remove() already mean removing this
            taskFutures.get(taskDir).remove(this);

            // ensure we won't call any listener method
            done = true;
        }

        /**
         * Caller is no longer interested in starting the work, and if no one is, we should avoid
         * trying to acquire the lock.
         */
        public synchronized void cancel() {
            remove();
            // TODO notify that we're not listening any more
        }

        private synchronized void ready() {
            if (done) {
                return;
            }
            remove();
            listener.onReady(new CacheResult(taskDir));
        }

        private synchronized void failure() {
            if (done) {
                return;
            }
            remove();
            listener.onFailure(new CacheResult(taskDir));
        }
    }
    /**
     * Returns a future which is successful if the tasks either finishes normally or reports an error.
     * The future only fails if there was a problem in managing the cache - this is a fatal problem
     * but doesn't reflect that there was an issue with doing the requested work.
     *
     * Note that this method does not actually block, but notifies when the task is finished. This
     * cannot directly be canceled, though the notification that a task was successful or failed can
     * be ignored (though notification on ready-to-build cannot be ignored). If the listener is told
     * to start the work, it will happen before this method returns.
     *
     * @param taskDetails details about the work being requested to either find existing work or
     *                    make a new location for it
     * @param listener an instance to be notified of the state of the task. If onReady is called, the work
     *                 may not be canceled
     */
    public void waitForTask(CollectedTaskInputs taskDetails, Listener listener) {
        assert taskDetails.getInputs().stream().allMatch(Input::hasContents);

        Murmur3F murmur3F = new Murmur3F();
        byte[] taskSummaryContents = taskSummaryContents(taskDetails).getBytes(StandardCharsets.UTF_8);
        murmur3F.update(taskSummaryContents);
        String hashString = murmur3F.getValueHexString();

        final Path taskDir = taskDir(taskDetails.getProject().getKey(), hashString, taskDetails.getTaskFactory().getOutputType());

        PendingCacheResult cancelable = new PendingCacheResult(taskDir, listener);
        taskFutures.computeIfAbsent(taskDir, ignore -> Collections.newSetFromMap(new ConcurrentHashMap<>())).add(cancelable);
        try {
            Path outputDir = outputDir(taskDir);

            // make sure the parent dir exists, we'll need it to one way or the other
            if (!taskDir.getParent().toFile().exists()) {
                Files.createDirectories(taskDir.getParent());
            }
            // first check if it isn't already on disk

            // try to create the task directory - if we succeed, we own it (this is atomic), if we fail, someone else already made it and we wait for them to finish
            //TODO one more check here that we even want to make this and start the work
            if (taskDir.toFile().mkdir()) {
                // caller can begin work right away
                Files.createDirectory(outputDir);
                Files.createFile(logFile(taskDir));
                Files.write(cacheSummary(taskDir), taskSummaryContents);
                cancelable.ready();
                return;
            }

            // caller will need to wait until the current owner completes it
            //TODO register the future instance so the service can let us know when it is up

            // set up markers in case we finish registration very fast
            Path successMarker = successMarker(taskDir);
            Path failureMarker = failureMarker(taskDir);
            knownMarkers.put(successMarker, taskDir);
            knownMarkers.put(failureMarker, taskDir);

            // register to watch if a marker is made so we can get a call back, then check for existing markers
            WatchKey key = registerWatchCreate(taskDir);

            // check once more if we can take over the task dir, if we raced with the registration
            //TODO one more check here that we even want to make this and start the work
            if (taskDir.toFile().mkdir()) {
                //TODO mark this as "nevermind" further?
                key.cancel();
                Files.createDirectory(outputDir);
                Files.createFile(logFile(taskDir));
                cancelable.ready();
                return;
            }

            if (successMarker.toFile().exists()) {
                // make sure we know it was successful
                knownOutputs.computeIfAbsent(taskDir, this::makeOutput);
                // already finished, success, no need to actually wait
                cancelable.success();
                //TODO mark as "nevermind" further?
                key.cancel();
                return;
            }

            if (failureMarker.toFile().exists()) {
                // already finished, failure, no need to actually wait
                cancelable.failure();
                //TODO mark as "nevermind" further?
                key.cancel();
                return;
            }

            // This task dir isn't owned by this process, and it might be stale
            if (!runningTasks.contains(taskDir)) {
                FileTime lastModifiedTime = Files.getLastModifiedTime(taskDir);
                FileTime limit = FileTime.from(Instant.now().minusSeconds(MAX_STALE_AGE));
                if (lastModifiedTime.compareTo(limit) < 0) {
                    //directory hasn't been updated, it must be stale, take over
                    System.out.println("STALE BUILD DETECTED - build was stale after " + MAX_STALE_AGE + " seconds, deleting it to take over: " + taskDir);
                    System.out.println("File was last modified at " + lastModifiedTime);
                    System.out.println("Expected it to be after " + limit);
                    deleteRecursively(taskDir);
                }
            }
        } catch (IOException ioException) {
            cancelable.error(new IOException("Error when interacting with the disk cache", ioException));
        }

        // we're waiting for real now, give up on this thread
    }

    /**
     * Helper to deal with correctly watching paths using the mac-specific watch impl
     */
    private WatchKey registerWatchCreate(Path taskDir) throws IOException {
        final Watchable watchable;
        if (IS_MAC) {
            watchable = new WatchablePath(taskDir);
        } else {
            watchable = taskDir;
        }
        return watchable.register(this.service, StandardWatchEventKinds.ENTRY_CREATE);
    }

    public void markFinished(CacheResult successfulResult) {
        try {
            this.knownOutputs.put(successfulResult.taskDir, makeOutput(successfulResult.taskDir));
            Files.createFile(successMarker(successfulResult.taskDir));
        } catch (IOException ioException) {
            //TODO need to basically stop everything if we can't write files to cache
            throw new UncheckedIOException(ioException);
        }
    }

    public void markFailed(CacheResult failedResult) {
        try {
            Files.createFile(failureMarker(failedResult.taskDir));
            new RuntimeException().printStackTrace();
        } catch (IOException ioException) {
            //TODO need to basically stop everything if we can't write files to cache
            throw new UncheckedIOException(ioException);
        }
    }

    public Optional<CacheResult> getCacheResult(Path taskDir) {
        if (Files.exists(taskDir) || Files.exists(successMarker(taskDir))) {
            CacheResult result = new CacheResult(taskDir);
            knownOutputs.computeIfAbsent(taskDir, this::makeOutput);
            return Optional.of(result);
        }
        return Optional.empty();
    }

}
