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
import io.methvin.watcher.changeset.ChangeSetListener;
;

public class FileService {

    private final DirectoryWatcher  watcher;
    private final ChangeSetListener listener;
    private       AtomicBoolean     run;
    private Map<Path, CachedProject>       projects        = new HashMap<>();

    public FileService(CachedProject... cachedProjects) {
        int                            watchSize       = 0;
        Map<CachedProject, List<Path>> projectsToWatch = new HashMap<>();


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
    }

    public void start() {
        this.watcher.watchAsync();

        UserInputRunner inputRunner = new UserInputRunner(run, this.listener, projects);
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

    public static class UserInputRunner implements Runnable {
        private AtomicBoolean            run;
        private ChangeSetListener        listener;
        private Map<Path, CachedProject> projects;

        public UserInputRunner(AtomicBoolean run, ChangeSetListener listener, Map<Path, CachedProject> projects) {
            this.run = run;
            this.listener = listener;
            this.projects = projects;
        }

        @Override
        public void run() {
            while (run.get()) {
                try {
                    System.out.println("waiting read");
                    System.in.read();
                    Map<Path, ChangeSet> changeSets = listener.getChangeSet();

                    System.out.println("received changeSet " + changeSets);

                    for (Map.Entry<Path, ChangeSet> entry : changeSets.entrySet()) {
                        Path path = entry.getKey();
                        CachedProject cachedProject = projects.get(path);//entry.getKey();
                        ChangeSet changeSet = entry.getValue(); // we aren't doing anything with this yet.

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
