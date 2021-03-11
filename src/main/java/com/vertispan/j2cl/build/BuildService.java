package com.vertispan.j2cl.build;

import com.vertispan.j2cl.build.impl.CollectedTaskInputs;
import io.methvin.watcher.hashing.FileHash;
import io.methvin.watcher.hashing.FileHasher;

import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

public class BuildService {
    private final TaskRegistry taskRegistry;
    private final TaskScheduler taskScheduler;
    private final DiskCache diskCache;

    private final Map<Input, CollectedTaskInputs> inputs = new HashMap<>();

    // hashes of each file in each project
    private final Map<Project, Map<Path, FileHash>> currentProjectSourceHash = new HashMap<>();

    public BuildService(TaskRegistry taskRegistry, TaskScheduler taskScheduler, DiskCache diskCache) {
        this.taskRegistry = taskRegistry;
        this.taskScheduler = taskScheduler;
        this.diskCache = diskCache;
    }

    /**
     * Specifies a project+task that this service is responsible for, should be called once for each
     * project that will be built, with the configuration expected. This configuration will be applied
     * to all projects - if conflicting configurations are applied to some work
     */
    public void assignProject(Project project, String finalTask, Map<String, String> config) {
        // find the tasks and their upstream tasks
        collectTasksFromProject(finalTask, project, config, inputs);

        // make sure we have the starting hash of each project, files in the project
        ensureProjectsHashed(inputs.keySet().stream().map(Input::getProject).collect(Collectors.toSet()));
    }

    private void ensureProjectsHashed(Set<Project> projects) {
        //TODO probably lean on the watch impl for this
        projects.stream().filter(Project::hasSourcesMapped).m;
    }

    private void collectTasksFromProject(String taskName, Project project, Map<String, String> config, Map<Input, CollectedTaskInputs> collectedSoFar) {
        Input newInput = new Input(project, taskName);
        if (collectedSoFar.containsKey(newInput)) {
            // don't build a step twice
//            return collectedSoFar.get(newInput);
            return;
        }
        CollectedTaskInputs collectedInputs = new CollectedTaskInputs(project);
//        TaskFactory.setCollectorForThread(collectedInputs);
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
                // stop here, we'll handle this on the fly and point it at the actual sources, current hashes
                if (input.getProject().hasSourcesMapped()) {
                    // mark this as something to watch, let them get hashed automatically
                } else {
                    // unpack sources to somewhere reusable and hash contents
                    diskCache.waitForTask(CollectedTaskInputs.jar(input.getProject().getSourceRoots())).;
                }
                continue;
            }
            collectTasksFromProject(input.getOutputType(), input.getProject(), config, collectedSoFar);
        }
    }

//    /**
//     * Manually triggers an update. Probably shouldn't be used except for testing?
//     */
//    public void updateFiles() {
//        for (CollectedTaskInputs taskInputs : inputs.values()) {
//            taskInputs.
//        }
//    }

    public synchronized void initialHashes(Project project, Map<Path, FileHash> files) {
        triggerChanges(project, files, Collections.emptyMap(), Collections.emptySet());
    }

    /**
     * Marks that a file has been created, deleted, or modified in the given project.
     */
    public synchronized void triggerChanges(Project project, Map<Path, FileHash> createdFiles, Map<Path, FileHash> changedFiles, Set<Path> deletedFiles) {
        //TODO this should be triggered by the watch service, or read from it at least
        Map<Path, FileHash> hashes = currentProjectSourceHash.get(project);
        hashes.keySet().removeAll(deletedFiles);
        assert hashes.keySet().stream().noneMatch(createdFiles.keySet()::contains) : "File already exists, can't be added";
        hashes.putAll(createdFiles);
        assert hashes.keySet().containsAll(changedFiles.keySet()) : "File doesn't exist, can't be modified";
        hashes.putAll(changedFiles);

        // with all projects updated by this batch, we can rebuild everything
    }

    public synchronized CompletableFuture<Void> requestBuild(Project project, String finalTask) {
        CollectedTaskInputs collectedTaskInputs = inputs.get(new Input(project, finalTask));
        assert collectedTaskInputs.getTask() instanceof TaskFactory.FinalOutputTask : "Cannot request a non-final task!";

        // mark current hashes of raw inputs

        return taskScheduler.submit(inputs.values());
    }

}
