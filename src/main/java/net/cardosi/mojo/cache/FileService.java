package net.cardosi.mojo.cache;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

import io.methvin.watcher.DirectoryWatcher;
import io.methvin.watcher.changeset.ChangeSet;
import io.methvin.watcher.changeset.ChangeSetEntry;
import io.methvin.watcher.changeset.ChangeSetListener;
import io.methvin.watcher.hashing.FileHash;
;

public class FileService {

    private final DirectoryWatcher          watcher;
    private final ChangeSetListener         listener;
    private       AtomicBoolean             run       = new AtomicBoolean(false);
    private final Map<Path, CachedProject>  projects  = new HashMap<>();
    private final DiskCache                 diskCache;

    public FileService(DiskCache diskCache, CachedProject... cachedProjects) {
        this.diskCache = diskCache;

        List<CachedProject> cachedProjectsList = new ArrayList<>();

        // need all reactor projects
        for (CachedProject project : cachedProjects) {
            cachedProjectsList.add(project);
            cachedProjectsList.addAll(project.getChildren().stream().filter( p -> p.hasSourcesMapped() ).collect(Collectors.toList()));
        }

        for (CachedProject project : cachedProjectsList) {
            project.compileSourceRoots().stream().map(Paths::get).forEach(p -> projects.put(p, project));
        }

        this.listener = new ChangeSetListener();
        try {
            this.watcher = DirectoryWatcher.builder()
                                           .paths(new ArrayList<>(projects.keySet()))
                                           .listener(listener)
                                           .fileHashing(true)
                                           .build();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        // Ensure all of the hashes are cached, before the tasks need them. Otherwise it'll call hash twice per Path.
        watcher.pathHashes().entrySet().stream()
               .forEach(entry -> diskCache.getHashes().put(entry.getKey(),
                                                           entry.getValue().asBytes()));

    }

    public void start() {
        // guarantee this goes true
        while ( run.compareAndSet(false,true) ) {
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
            }
        }

        watcher.watchAsync();

        UserInputRunner inputRunner = new UserInputRunner(watcher, diskCache, run,
                                                          listener, projects);
        new Thread(inputRunner).start();
    }

    public void stop() {
        // guarantee this goes false
        while ( run.compareAndSet(true,false) ) {
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
            }
        }

        try {
            this.watcher.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public static class UserInputRunner implements Runnable {
        private final AtomicBoolean            run;
        private final ChangeSetListener        listener;
        private final Map<Path, CachedProject> projects;
        private final DirectoryWatcher         watcher;
        private final DiskCache                diskCache;

        public UserInputRunner(DirectoryWatcher  watcher, DiskCache diskCache, AtomicBoolean run,
                               ChangeSetListener listener, Map<Path, CachedProject> projects) {
            this.watcher = watcher;
            this.diskCache = diskCache;
            this.run = run;
            this.listener = listener;
            this.projects = projects;
        }

        @Override
        public void run() {
            while (run.get()) {
                try {
                    System.out.println("Waiting for key press followed by enter...");
                    System.in.read();

                    // Calls the thread safe method, that is an Exchange pattern to get the accumulated ChangeSet
                    Map<Path, ChangeSet> changeSets = listener.getChangeSet();
                    // System.out.println("received changeSet " + changeSets); // debug message

                    // iterate all the Paths in all the ChangeSets, and get and put their hashes.
                    // So only files that changed will have their hash cache entry updated.
                    // This cache is then used so that re-hashing CachedProjects is faster.
                    Map<Path, FileHash> updatedPathHashes = watcher.pathHashes();
                    for (Map.Entry<Path, ChangeSet> changeSet : changeSets.entrySet()) {

                        for(ChangeSetEntry entry : changeSet.getValue().created()) {
                            diskCache.getHashes().put(entry.path(), updatedPathHashes.get(entry.path()).asBytes());
                        }
                        for(ChangeSetEntry entry : changeSet.getValue().modified()) {
                            diskCache.getHashes().put(entry.path(), updatedPathHashes.get(entry.path()).asBytes());
                        }
                        for(ChangeSetEntry entry : changeSet.getValue().deleted()) {
                            diskCache.getHashes().remove(entry.path());
                        }
                    }

                    for (Map.Entry<Path, ChangeSet> entry : changeSets.entrySet()) {
                        Path path = entry.getKey();
                        CachedProject cachedProject = projects.get(path);//entry.getKey();
                        ChangeSet changeSet = entry.getValue(); // We aren't doing anything with this yet.
                                                                // But eventually it can be used for a more target MakeDirty

                        // System.out.println("MakeDirty..." + cachedProject.getArtifact()); // debug message
                        cachedProject.markDirty();
                    }
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }
    }
}
