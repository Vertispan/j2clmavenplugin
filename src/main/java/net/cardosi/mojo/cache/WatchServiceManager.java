package net.cardosi.mojo.cache;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

import io.methvin.watcher.DirectoryChangeEvent;
import io.methvin.watcher.DirectoryChangeListener;
import io.methvin.watcher.DirectoryWatcher;

public class WatchServiceManager {

    private final DirectoryWatcher watcher;
    private final ChangeSetListener listener;
    private AtomicBoolean                   run;

    public WatchServiceManager(CachedProject... cachedProjects) {
        int watchSize = 0;
        Map<CachedProject, List<Path>> projectsToWatch = new HashMap<>();
        Map<Path, CachedProject> contexts = new HashMap<>();

        List<CachedProject> cachedProjectsList = new ArrayList<>();

        // need all reactor projects
        for (CachedProject project : cachedProjects) {
            cachedProjectsList.add(project);
            cachedProjectsList.addAll(project.getChildren().stream().filter( p -> p.hasSourcesMapped() ).collect(Collectors.toList()));
        }

        for (CachedProject project : cachedProjectsList) {
            project.getCompileSourceRoots().stream().map(Paths::get).forEach(p -> contexts.put(p, project));
        }

        this.run = new AtomicBoolean(true);
        this.listener = new ChangeSetListener(contexts);
        try {
            this.watcher = DirectoryWatcher.builder()
                                           .paths(new ArrayList<>(contexts.keySet()))
                                           .listener(listener)
                                           .fileHashing(true)
                                           .build();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public void start() {
        this.watcher.watchAsync();

        UserInputRunner inputRunner = new UserInputRunner(run, this.listener);
        new Thread(inputRunner).start();
    }

    public void stop() {
        // guarantee this goes false, so all threads stop.
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

    public static class ChangeSetListener implements DirectoryChangeListener {
        private Map<CachedProject, ChangeSet> changeSets;
        private Map<Path, CachedProject>      contexts;

        // Lock is used as a simple Exchange pattern, so the UserInput thread can safely consume normalisedChangedSet
        private Object lock = new Object() {
        };

        public ChangeSetListener(Map<Path, CachedProject> contexts) {
            this.contexts = contexts;
            this.changeSets = new HashMap<>();
        }

        @Override
        public void onEvent(DirectoryChangeEvent event) {
            Path rootPath = event.rootPath();
            Path path = event.path();
            CachedProject cachedProject = contexts.get(rootPath);

            synchronized (lock) {

                // Maintain a ChangeSet per CachedProject
                ChangeSet changeSet = changeSets.get(cachedProject);
                System.out.println(cachedProject);
                System.out.println(changeSet);
                if (changeSet == null) {
                    changeSet = new ChangeSet();
                    changeSets.put(cachedProject, changeSet);
                }

                // This logic assumes events might come out of order, i.e. a delete before a create, and attempts to handle this gracefully.
                switch (event.eventType()) {
                    case CREATE:
                        // Remove any MODIFY, quicker to just remove than check and remove.
                        changeSet.modified.remove(path);

                        // Only add if DELETE does not already exist.
                        if (!changeSet.deleted.remove(path)) {
                            changeSet.created.add(path);
                        }
                        break;
                    case MODIFY:
                        if (!changeSet.deleted.contains(path) && !changeSet.created.contains(path) ) {
                            // Only add the MODIFY if a CREATE or DELETE does not already exist.
                            changeSet.modified.add(path);
                        }
                        break;
                    case DELETE:
                        // Always added DELETE, quicker to just remove CREATE and MODIFY, than check for them.
                        changeSet.created.remove(path);
                        changeSet.modified.remove(path);

                        changeSet.deleted.add(path);
                        break;
                    case OVERFLOW:
                        throw new IllegalStateException("OVERFLOW not yet handled");
                }
                System.out.println(changeSet);
            }

        }

        public Map<CachedProject, ChangeSet> getChangeSet() {
            Map<CachedProject, ChangeSet> returnMap;
            synchronized (lock) {
                returnMap = changeSets;
                changeSets = new HashMap<>();
            }
            return returnMap;
        }
    }

    public static class ChangeSet {
        private Set<Path> created = new HashSet<>();
        private Set<Path> modified = new HashSet<>();
        private Set<Path> deleted = new HashSet<>();

        public Set<Path> created() {
            return created;
        }

        public Set<Path> modified() {
            return modified;
        }

        public Set<Path> deleted() {
            return deleted;
        }

        @Override public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (o == null || getClass() != o.getClass()) {
                return false;
            }

            ChangeSet changeSet = (ChangeSet) o;

            if (!created.equals(changeSet.created)) {
                return false;
            }
            if (!modified.equals(changeSet.modified)) {
                return false;
            }
            return deleted.equals(changeSet.deleted);
        }

        @Override public int hashCode() {
            int result = created.hashCode();
            result = 31 * result + modified.hashCode();
            result = 31 * result + deleted.hashCode();
            return result;
        }

        @Override public String toString() {
            return "ChangeSet{" +
                   "created=" + created +
                   ", modified=" + modified +
                   ", deleted=" + deleted +
                   '}';
        }
    }

    public static class UserInputRunner implements Runnable {
        private AtomicBoolean   run;
        private ChangeSetListener listener;

        public UserInputRunner(AtomicBoolean run, ChangeSetListener listener) {
            this.run = run;
            this.listener = listener;
        }

        @Override
        public void run() {
            while (run.get()) {
                try {
                    System.out.println("waiting read");
                    System.in.read();
                    Map<CachedProject, ChangeSet> changeSet = listener.getChangeSet();

                    System.out.println("received changeSet " + changeSet);

                    for (Map.Entry<CachedProject, ChangeSet> projectChangeSet : changeSet.entrySet()) {
                        CachedProject cachedProject = projectChangeSet.getKey();
                        ChangeSet pathChanges = projectChangeSet.getValue(); // we aren't doing anything with this yet.

                        System.out.println("MakeDirty..." + cachedProject.getArtifact());
                        cachedProject.markDirty();
                    }
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }
    }
}
