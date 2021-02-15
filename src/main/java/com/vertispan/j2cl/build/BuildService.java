package com.vertispan.j2cl.build;

import com.vertispan.j2cl.build.impl.CollectedTaskInputs;
import io.methvin.watcher.hashing.FileHasher;

import java.nio.file.Path;
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
    private final Map<Project, Map<Path, String>> currentProjectSourceHash = new HashMap<>();

    public BuildService(TaskRegistry taskRegistry, TaskScheduler taskScheduler, DiskCache diskCache) {
        this.taskRegistry = taskRegistry;
        this.taskScheduler = taskScheduler;
        this.diskCache = diskCache;
    }

    /**
     * Specifies a project+task that this service is responsible for.
     */
    public void assignProject(Project project, String finalTask, Map<String, String> config) {
        // find the tasks and their upstream tasks
        collectTasksFromProject(finalTask, project, config, inputs);

        // make sure we have the starting hash of each project, files in the project
        ensureProjectsHashed(inputs.keySet().stream().map(Input::getProject).collect(Collectors.toSet()));
    }

    private void ensureProjectsHashed(Set<Project> projects) {
        //TODO
    }

    private void collectTasksFromProject(String taskName, Project project, Map<String, String> config, Map<Input, CollectedTaskInputs> collectedSoFar) {
        Input newInput = new Input(project, taskName);
        if (collectedSoFar.containsKey(newInput)) {
            // don't build a step twice
//            return collectedSoFar.get(newInput);
            return;
        }
        CollectedTaskInputs collectedInputs = new CollectedTaskInputs(taskName, project);
        TaskFactory.setCollectorForThread(collectedInputs);
        PropertyTrackingConfig propertyTrackingConfig = new PropertyTrackingConfig(config);
        TaskFactory.Task task = taskRegistry.taskForOutputType(taskName).resolve(project, propertyTrackingConfig);
        collectedInputs.setTask(task);
        collectedInputs.setUsedConfigs(propertyTrackingConfig.getUsedConfigs());
        collectedSoFar.put(newInput, collectedInputs);
        for (Input input : collectedInputs.getInputs()) {
            collectTasksFromProject(input.getOutputType(), input.getProject(), config, collectedSoFar);
        }
    }

    /**
     * Manually triggers an update. Probably shouldn't be used except for testing?
     */
    public void updateFiles() {

    }

    /**
     * Marks that a file has been created, deleted, or modified in the given project.
     */
    public synchronized void triggerChanges(Project project, Set<Path> createdFiles, Set<Path> changedFiles, Set<Path> deletedFiles) {
        //TODO this should be triggered by the watch service, or read from it at least
        Map<Path, String> hashes = currentProjectSourceHash.get(project);
        hashes.keySet().removeAll(deletedFiles);
        for (Path createdFile : createdFiles) {
            hashes.put(createdFile, FileHasher.DEFAULT_FILE_HASHER.hash(createdFile).asString());
        }
        for (Path changedFile : changedFiles) {
            hashes.put(changedFile, FileHasher.DEFAULT_FILE_HASHER.hash(changedFile).asString());
        }
    }

    public CompletableFuture<Void> requestBuild(Project project, String finalTask) {
        CollectedTaskInputs collectedTaskInputs = inputs.get(new Input(project, finalTask));

        assert collectedTaskInputs.getTask() instanceof TaskFactory.FinalOutputTask : "Cannot request a non-final task!";


        diskCache.getCacheKey(null, collectedTaskInputs.getTask(), collectedTaskInputs.getUsedConfigs(), collectedTaskInputs.getInputs());
    }

}
