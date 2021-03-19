package com.vertispan.j2cl.build;

import com.vertispan.j2cl.build.impl.CollectedTaskInputs;
import io.methvin.watcher.PathUtils;
import io.methvin.watcher.hashing.FileHash;
import io.methvin.watcher.hashing.FileHasher;
import io.methvin.watchservice.MacOSXListeningWatchService;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.*;
import java.nio.file.WatchService;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Manages the cached task inputs and outputs.
 */
public abstract class DiskCache {
    public class CacheResult {
        private final Path taskDir;

        public CacheResult(Path taskDir) {
            this.taskDir = taskDir;
        }

        public Path logFile() {
            //TODO finish building a logger that will write to this
            return DiskCache.this.logFile(taskDir);
        }
        public Path outputDir() {
            return DiskCache.this.outputDir(taskDir);
        }

        public TaskOutput output() {
            return knownOutputs.get(taskDir);
        }

        public void markSuccess() {
            markFinished(this);
        }
        public void markFailure() {
            markFailed(this);
        }

    }

    protected final File cacheDir;
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
    private final Map<Path, Set<PendingCacheResult>> taskFutures = new HashMap<>();

    public DiskCache(File cacheDir) throws IOException {
        this.cacheDir = cacheDir;
        cacheDir.mkdirs();
        if (!cacheDir.exists() && !cacheDir.isDirectory()) {
            throw new IllegalArgumentException("Can't use " + cacheDir + ", failed to create it, or already exists and isn't a directory");
        }

        boolean isMac = System.getProperty("os.name").toLowerCase().contains("mac");
        if (isMac) {
            service = new MacOSXListeningWatchService();
        } else {
            service = cacheDir.toPath().getFileSystem().newWatchService();
        }

        watchThread.start();
    }

    private void checkForWork() {
        try {
            WatchKey key;
            while ((key = service.take()) != null) {
                for (WatchEvent<?> event : key.pollEvents()) {
                    if (event.kind() == StandardWatchEventKinds.ENTRY_CREATE) {
                        // task ended one way or the other
                        Path path = (Path) event.context();
                        Path taskDir = knownMarkers.get(path);
                        Set<PendingCacheResult> listeners = taskFutures.get(taskDir);
                        if (path.equals(successMarker(taskDir))) {
                            try {
                                knownOutputs.put(path, makeOutput(path));
                                listeners.forEach(PendingCacheResult::success);
                            } catch (UncheckedIOException ioException) {
                                // failure to hash is pretty terrible, we're in trouble
                                ioException.printStackTrace();
                                listeners.forEach(l -> l.error(ioException));
                            }
                        } else {
                            assert path.equals(failureMarker(taskDir));
                            listeners.forEach(PendingCacheResult::failure);
                        }
                    } else if (event.kind() == StandardWatchEventKinds.ENTRY_DELETE) {
                        // task was canceled, we should attempt to take over
                        Path taskDir = (Path) event.context();
                        Set<PendingCacheResult> listeners = taskFutures.get(taskDir);

                        //TODO prep for new attempt, attempt to take over instead of this
//                        future.complete(new CacheResult(Status.NOT_STARTED, taskDir));
                        listeners.forEach(l -> l.error(new IllegalStateException("Existing task was canceled, not yet supported")));
                    }
                }
                key.reset();
            }
        } catch (InterruptedException e) {
            // asked to shut down, time to stop
            // TODO mark all pending work as canceled?
        }
    }

    private TaskOutput makeOutput(Path taskDir) {
        Path outputDir = outputDir(taskDir);
        Map<Path, FileHash> fileHashes = hashContents(outputDir);
        return new TaskOutput(outputDir, fileHashes);
    }

    /**
     * Helper like PathUtils.initWatcherState to produce the relative paths of any files
     * in a path, and their corresponding hashes.
     */
    public static Map<Path, FileHash> hashContents(Path path) {
        HashMap<Path, FileHash> fileHashes = new HashMap<>();
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
                            FileHash hash = PathUtils.hash(fileHasher, file);
                            if (hash == null) {
                                //file could have been deleted or was otherwise unreadable
                                //TODO how do we handle this? For now skipping as PathUtils does
                            } else {
                                fileHashes.put(path.relativize(file), hash);
                            }
                            return FileVisitResult.CONTINUE;
                        }
                    });
        } catch (IOException ioException) {
            throw new UncheckedIOException(ioException);
        }
        return fileHashes;
    }

    public void close() throws IOException {
        service.close();
        watchThread.interrupt();
    }

    protected abstract Path taskDir(CollectedTaskInputs inputs);
    protected abstract Path successMarker(Path taskDir);
    protected abstract Path failureMarker(Path taskDir);
    protected abstract Path logFile(Path taskDir);
    protected abstract Path outputDir(Path taskDir);

    interface Listener {
        void onReady(CacheResult result);
        void onFailure(CacheResult result);
        void onError(Throwable throwable);
        void onSuccess(CacheResult result);
    }
    public class PendingCacheResult {
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
            listener.onSuccess(new CacheResult(taskDir));
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
     * @param taskDetails details about the work being requested to either find existing work or
     *                    make a new location for it
     * @param listener an instance to be notified of the state of the task. If onReady is called, the work
     *                 may not be canceled
     * @return a future that will describe where details about the task should be located
     */
    public PendingCacheResult waitForTask(CollectedTaskInputs taskDetails, Listener listener) {
        final Path taskDir = taskDir(taskDetails);
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
                cancelable.ready();
                return cancelable;
            }

            // caller will need to wait until the current owner completes it
            //TODO register the future instance so the service can let us know when it is up

            // set up markers in case we finish registration very fast
            Path successMarker = successMarker(taskDir);
            Path failureMarker = failureMarker(taskDir);
            knownMarkers.put(successMarker, taskDir);
            knownMarkers.put(failureMarker, taskDir);

            // register to watch if a marker is made so we can get a call back, then check for existing markers
            WatchKey key = taskDir.register(this.service, StandardWatchEventKinds.ENTRY_CREATE);

            // check once more if we can take over the task dir, if we raced with the registration
            //TODO one more check here that we even want to make this and start the work
            if (taskDir.toFile().mkdir()) {
                //TODO mark this as "nevermind" further?
                key.cancel();
                Files.createDirectory(outputDir);
                Files.createFile(logFile(taskDir));
                cancelable.ready();
                return cancelable;
            }

            if (successMarker.toFile().exists()) {
                // already finished, success, no need to actually wait
                cancelable.success();
                //TODO mark as "nevermind" further?
                key.cancel();
                return cancelable;
            }

            if (failureMarker.toFile().exists()) {
                // already finished, failure, no need to actually wait
                cancelable.failure();
                //TODO mark as "nevermind" further?
                key.cancel();
                return cancelable;
            }
        } catch (IOException ioException) {
            cancelable.error(new IOException("Error when interacting with the disk cache", ioException));
        }

        // we're waiting for real now, give up on this thread
        return cancelable;
    }

    public void markFinished(CacheResult successfulResult) {
        try {
            Files.createFile(successMarker(successfulResult.taskDir));
        } catch (IOException ioException) {
            //TODO need to basically stop everything if we can't write files to cache
            throw new UncheckedIOException(ioException);
        }
    }

    public void markFailed(CacheResult failedResult) {
        try {
            Files.createFile(failureMarker(failedResult.taskDir));
        } catch (IOException ioException) {
            //TODO need to basically stop everything if we can't write files to cache
            throw new UncheckedIOException(ioException);
        }
    }

}
