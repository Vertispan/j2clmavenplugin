package net.cardosi.mojo.cache;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileSystem;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public class WatchManager {
    Map<FileSystem, List<Path>> fileSystemsToWatch;
    List<WatchServiceRunner> watchServices;
    private CachedProject cachedProject;
    private AtomicBoolean run;

    public WatchManager(Map<FileSystem, List<Path>> fileSystemsToWatch, CachedProject cachedProject) {
        this.fileSystemsToWatch = fileSystemsToWatch;
        this.watchServices = new ArrayList<>(fileSystemsToWatch.size());
        this.cachedProject = cachedProject;
        this.run = new AtomicBoolean();

        try {
            for (Map.Entry<FileSystem, List<Path>> entry : fileSystemsToWatch.entrySet()) {
                WatchService        watchService  = entry.getKey().newWatchService();
                Map<Path, WatchKey> pathWatchKeys = new HashMap<>();
                for (Path path : entry.getValue()) {
                    watchDirectoryAndDescendants(watchService, pathWatchKeys, path);
                }

                WatchServiceRunner runner = new WatchServiceRunner(run, watchService, pathWatchKeys, cachedProject);
                watchServices.add(runner);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public void start() {
        UserInputRunner inputRunner = new UserInputRunner(run, null);
        new Thread(inputRunner).start();

        for(WatchServiceRunner watchRunner : watchServices) {
            new Thread(watchRunner).start();
        }
    }

    public void stop() {
        // guarantee this goes false, so all threads stop.
        while ( run.compareAndSet(true,false) ) {
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
            }
        }
    }

    private static void watchDirectoryAndDescendants(WatchService watchService, Map<Path, WatchKey> pathWatchKeys, Path path) throws IOException {
        Files.walkFileTree(path, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult preVisitDirectory(Path path, BasicFileAttributes basicFileAttributes) throws IOException {
                WatchKey key = path.register(watchService, StandardWatchEventKinds.ENTRY_CREATE, StandardWatchEventKinds.ENTRY_DELETE, StandardWatchEventKinds.ENTRY_MODIFY);
                pathWatchKeys.put(path, key);
                System.out.println("watch " + path);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    public static class UserInputRunner implements Runnable {
        private AtomicBoolean run;
        private BlockingQueue<Set<PathChanged>> fileSystemChanges;

        public UserInputRunner(AtomicBoolean run, BlockingQueue<Set<PathChanged>> fileSystemChanges) {
            this.run = run;
            this.fileSystemChanges = fileSystemChanges;
        }

        @Override
        public void run() {
            while (run.get()) {
                try {
                    System.in.read();
                    List<Set<PathChanged>> changes = new ArrayList<>();
                    Set<PathChanged> set = fileSystemChanges.take();
                    // Need to fully drain. Use 3 attemps, with a short sleep.
                    int pollAttemptsLeft = 3;
                    while (pollAttemptsLeft >= 0) {
                        if (set != null) {
                            changes.add(set);
                            pollAttemptsLeft = 3;
                        } else {
                            pollAttemptsLeft--;
                        }
                        Thread.sleep(50);
                        set = fileSystemChanges.poll();
                    }
                } catch (InterruptedException | IOException e) {
                    e.printStackTrace();
                }
            }
        }
    }

    public static class WatchServiceRunner implements Runnable {
        private WatchService watchService;
        private Map<Path, WatchKey> pathWatchKeys;
        private CachedProject cachedProject;
        private AtomicBoolean run;


        public WatchServiceRunner(AtomicBoolean run, WatchService watchService, Map<Path, WatchKey> pathWatchKeys, CachedProject cachedProject) {
            this.run = run;
            this.watchService = watchService;
            this.pathWatchKeys = pathWatchKeys;
            this.cachedProject = cachedProject;
        }

        public void run() {
            while (run.get()) {
                try {
                    Set<PathChanged> fileSystemChanges = new HashSet<>();
                    WatchKey  key   = watchService.take();

                    // This attempts some degree of batching, and normalisation in the case of modifies on the same file, in the case of user batch operations, like refactoring.
                    // It will keep looping while the poll is returning. It will allow for up to
                    // 3 null polls, before ending the batch.
                    int pollAttemptsLeft = 3;
                    while (pollAttemptsLeft >= 0) {
                        if (key != null) {
                            for (WatchEvent<?> event : key.pollEvents()) {//clear the events out
                                WatchEvent.Kind<?> kind   = event.kind();
                                Path               parent = (Path) key.watchable();
                                Path               child  = parent.resolve((Path) event.context());

                                if (kind == StandardWatchEventKinds.ENTRY_DELETE) {
                                    fileSystemChanges.add(new PathChanged(kind, child));
                                    // deleted directories will not work with Files.isDirectory()
                                    // so just check for a key, for all entries
                                    WatchKey childKey = pathWatchKeys.remove(child);
                                    if (childKey != null) { // if it's not null, we know it was a folder
                                        childKey.cancel();
                                        System.out.println("unwatch " + child);
                                    }
                                } else if (kind == StandardWatchEventKinds.ENTRY_CREATE && child.toFile().isDirectory()) {
                                    watchDirectoryAndDescendants(watchService, pathWatchKeys, child);
                                }

                                fileSystemChanges.add(new PathChanged(kind, child));
                            }
                            key.reset();//reset to go again
                            pollAttemptsLeft = 3;
                        } else {
                            pollAttemptsLeft--;
                        }
                        key = watchService.poll(50, TimeUnit.MILLISECONDS);
                    }
                } catch (InterruptedException | IOException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }

    public static class PathChanged {
        private WatchEvent.Kind<?> kind;
        private Path path;

        public PathChanged(WatchEvent.Kind<?> kind, Path path) {
            this.kind = kind;
            this.path = path;
        }

        public WatchEvent.Kind<?> getKind() {
            return kind;
        }

        public Path getPath() {
            return path;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (o == null || getClass() != o.getClass()) {
                return false;
            }

            PathChanged that = (PathChanged) o;

            if (kind != null ? !kind.equals(that.kind) : that.kind != null) {
                return false;
            }
            return path != null ? path.equals(that.path) : that.path == null;
        }

        @Override public int hashCode() {
            int result = kind != null ? kind.hashCode() : 0;
            result = 31 * result + (path != null ? path.hashCode() : 0);
            return result;
        }
    }
}
