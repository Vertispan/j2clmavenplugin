package com.vertispan.j2cl.build;

import com.vertispan.j2cl.build.impl.CollectedTaskInputs;
import com.vertispan.j2cl.build.task.OutputTypes;
import com.vertispan.j2cl.build.task.TaskFactory;
import io.methvin.watcher.hashing.FileHash;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public class BuildService {
    private final TaskRegistry taskRegistry;
    private final TaskScheduler taskScheduler;
    private final DiskCache diskCache;

    // all registered project+task items that might need to be built, and their inputs
    private final Map<Input, CollectedTaskInputs> inputs = new HashMap<>();

    // hashes of each file in each project, updated under lock
    private final Map<Project, Map<Path, FileHash>> currentProjectSourceHash = new HashMap<>();

    private BlockingBuildListener prevBuild;

    public BuildService(TaskRegistry taskRegistry, TaskScheduler taskScheduler, DiskCache diskCache) {
        this.taskRegistry = taskRegistry;
        this.taskScheduler = taskScheduler;
        this.diskCache = diskCache;
    }

    /**
     * Specifies a project+task that this service is responsible for, should be called once for each
     * project that will be built, with the configuration expected. This configuration will be applied
     * to all projects - if conflicting configurations need to be applied to some work, it should be
     * submitted to separate BuildServices.
     */
    public void assignProject(Project project, String finalTask, Map<String, String> config) {
        // find the tasks and their upstream tasks
        collectTasksFromProject(finalTask, project, config, inputs);
    }

    private void collectTasksFromProject(String taskName, Project project, Map<String, String> config, Map<Input, CollectedTaskInputs> collectedSoFar) {
        Input newInput = new Input(project, taskName);
        if (collectedSoFar.containsKey(newInput)) {
            // don't build a step twice
//            return collectedSoFar.get(newInput);
            return;
        }
        CollectedTaskInputs collectedInputs = new CollectedTaskInputs(project);
        PropertyTrackingConfig propertyTrackingConfig = new PropertyTrackingConfig(config);

        // build the task lambda that we'll use here
        TaskFactory taskFactory = taskRegistry.taskForOutputType(taskName);
        collectedInputs.setTaskFactory(taskFactory);
        TaskFactory.Task task = taskFactory.resolve(project, propertyTrackingConfig);
        collectedInputs.setTask(task);

        // prevent the config object from being used incorrectly, where we can't detect its changes
        propertyTrackingConfig.close();

        collectedInputs.setUsedConfigs(propertyTrackingConfig.getUsedConfigs());
        collectedSoFar.put(newInput, collectedInputs);

        // prep any other tasks that are needed
        for (Input input : collectedInputs.getInputs()) {
            if (input.getOutputType().equals(OutputTypes.INPUT_SOURCES)) {
//                // stop here, we'll handle this on the fly and point it at the actual sources, current hashes
//                if (input.getProject().hasSourcesMapped()) {
//                    // mark this as something to watch, let them get hashed automatically
//                } else {
//                    // unpack sources to somewhere reusable and hash contents
//                    diskCache.waitForTask(CollectedTaskInputs.jar(input.getProject().getSourceRoots())).;
//                }
                continue;
            }
            collectTasksFromProject(input.getOutputType(), input.getProject(), config, collectedSoFar);
        }
    }

    /**
     * Assign the initial hashes for files in the project. Call if there is no watch service enabled.
     */
    public synchronized void initialHashes() {
        // for each project which has sources, hash them
        inputs.keySet().stream().map(Input::getProject).filter(Project::hasSourcesMapped)
                .forEach(project -> {
                    Map<Path, FileHash> hashes = project.getSourceRoots().stream()
                            .map(Paths::get)
                            .map(DiskCache::hashContents)
                            .map(Map::entrySet)
                            .flatMap(Set::stream)
                            .collect(Collectors.toMap(
                                    Map.Entry::getKey,
                                    Map.Entry::getValue,
                                    (a, b) -> {
                                        throw new IllegalStateException("Two paths in a project had the same file");
                                    }
                            ));
                    triggerChanges(project, hashes, Collections.emptyMap(), Collections.emptySet());

                });

    }

    /**
     * Marks that a file has been created, deleted, or modified in the given project.
     */
    public synchronized void triggerChanges(Project project, Map<Path, FileHash> createdFiles, Map<Path, FileHash> changedFiles, Set<Path> deletedFiles) {
        Map<Path, FileHash> hashes = currentProjectSourceHash.get(project);
        hashes.keySet().removeAll(deletedFiles);
        assert hashes.keySet().stream().noneMatch(createdFiles.keySet()::contains) : "File already exists, can't be added";
        hashes.putAll(createdFiles);
        assert hashes.keySet().containsAll(changedFiles.keySet()) : "File doesn't exist, can't be modified";
        hashes.putAll(changedFiles);

        // with all projects updated by this batch, we can rebuild everything -
        // callers will indicate it is time for this with requestBuild()
    }


    /**
     * Only one build can take place at a time, be sure to stop the previous build before submitting a new one,
     * or the new one will have to wait until the first finishes
     * @param buildListener support for notifications about the status of the work
     * @return an object which can cancel remaining unstarted work
     */
    public synchronized Cancelable requestBuild(BuildListener buildListener) throws InterruptedException {
        // wait for the previous build, if any, to finish
        if (prevBuild != null) {
            prevBuild.blockUntilFinished();
        }

        // TODO update inputs with the hash changes we've seen
        inputs.keySet().stream()
                .filter(i -> i.getProject().hasSourcesMapped())
                .forEach(i -> {

                });

        // this could possibly be more fine grained, only submit the projects which could be affected by changes
        prevBuild = new WrappedBlockingBuildListener(buildListener);
        return taskScheduler.submit(inputs.values(), prevBuild);
    }
    class WrappedBlockingBuildListener extends BlockingBuildListener {
        private final BuildListener wrapped;

        WrappedBlockingBuildListener(BuildListener wrapped) {
            this.wrapped = wrapped;
        }

        @Override
        public void onProgress(int completedCount, int startedCount, int pendingCount, String task, Project project, Activity activity) {
            wrapped.onProgress(completedCount, startedCount, pendingCount, task, project, activity);
        }

        @Override
        public void onSuccess() {
            super.onSuccess();
            wrapped.onSuccess();
        }

        @Override
        public void onFailure() {
            super.onFailure();
            wrapped.onFailure();
        }

        @Override
        public void onError(Throwable throwable) {
            super.onError(throwable);
            wrapped.onError(throwable);
        }
    }

}
