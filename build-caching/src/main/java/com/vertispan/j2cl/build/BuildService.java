package com.vertispan.j2cl.build;

import com.vertispan.j2cl.build.impl.CollectedTaskInputs;
import com.vertispan.j2cl.build.task.OutputTypes;
import com.vertispan.j2cl.build.task.TaskFactory;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class BuildService {
    private final TaskRegistry taskRegistry;
    private final TaskScheduler taskScheduler;
    private final DiskCache diskCache;

    // all registered project+task items that might need to be built, and their inputs
    private final Map<Input, CollectedTaskInputs> inputs = new HashMap<>();

    // hashes of each file in each project, updated under lock
    private final Map<Project, Map<Path, DiskCache.CacheEntry>> currentProjectSourceHash = new HashMap<>();

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
    public void assignProject(Project project, String finalTask, PropertyTrackingConfig.ConfigValueProvider config) {
        // find the tasks and their upstream tasks
        collectTasksFromProject(finalTask, project, config, inputs);
    }

    private void collectTasksFromProject(String taskName, Project project, PropertyTrackingConfig.ConfigValueProvider config, Map<Input, CollectedTaskInputs> collectedSoFar) {
        Input newInput = new Input(project, taskName);
        if (collectedSoFar.containsKey(newInput)) {
            // don't build a step twice
//            return collectedSoFar.get(newInput);
            return;
        }
        CollectedTaskInputs collectedInputs = new CollectedTaskInputs(project);
        if (!taskName.equals(OutputTypes.INPUT_SOURCES)) {
            PropertyTrackingConfig propertyTrackingConfig = new PropertyTrackingConfig(config);

            // build the task lambda that we'll use here
            TaskFactory taskFactory = taskRegistry.taskForOutputType(taskName);
            collectedInputs.setTaskFactory(taskFactory);
            if (taskFactory == null) {
                throw new NullPointerException("Missing task factory: " + taskName);
            }
            assert taskFactory.inputs.isEmpty();
            TaskFactory.Task task = taskFactory.resolve(project, propertyTrackingConfig);
            collectedInputs.setTask(task);
            collectedInputs.setInputs(new ArrayList<>(taskFactory.inputs));
            taskFactory.inputs.clear();

            // prevent the config object from being used incorrectly, where we can't detect its changes
            propertyTrackingConfig.close();
            collectedInputs.setUsedConfigs(propertyTrackingConfig.getUsedConfigs());
        } else {
            collectedInputs.setInputs(Collections.emptyList());
            collectedInputs.setUsedConfigs(Collections.emptyMap());
            collectedInputs.setTaskFactory(new InputSourceTaskFactory());
        }

        collectedSoFar.put(newInput, collectedInputs);

        // prep any other tasks that are needed
        for (Input input : collectedInputs.getInputs()) {

            // make sure we have sources, hashes
            if (input.getOutputType().equals(OutputTypes.INPUT_SOURCES)) {
                // stop here, we'll handle this on the fly and point it at the actual sources, current hashes
                // for jars, we unzip them as below - but requestBuild will handle reactor projects
                if (!input.getProject().hasSourcesMapped()) {
                    // unpack sources to somewhere reusable and hash contents

                    // TODO we could make this async instead of blocking, do them all at once
                    CollectedTaskInputs unpackJar = CollectedTaskInputs.jar(input.getProject());
                    BlockingBuildListener listener = new BlockingBuildListener();
                    taskScheduler.submit(Collections.singletonList(unpackJar), listener);
                    try {
                        listener.blockUntilFinished();
                        CountDownLatch latch = new CountDownLatch(1);
                        diskCache.waitForTask(unpackJar, new DiskCache.Listener() {
                            @Override
                            public void onReady(DiskCache.CacheResult result) {

                            }

                            @Override
                            public void onFailure(DiskCache.CacheResult result) {

                            }

                            @Override
                            public void onError(Throwable throwable) {

                            }

                            @Override
                            public void onSuccess(DiskCache.CacheResult result) {
                                // we know the work is done already, just grab the result dir
                                input.setCurrentContents(result.output());
                                latch.countDown();
                            }
                        });
                        latch.await();
                    } catch (InterruptedException e) {
                        throw new RuntimeException("Interrupted exception when unpacking!", e);
                    }
                    continue;
                } // else this is something to watch, let them get hashed automatically

            }


            collectTasksFromProject(input.getOutputType(), input.getProject(), config, collectedSoFar);
        }
    }

    /**
     * Assign the initial hashes for files in the project. Call if there is no watch service enabled.
     */
    public synchronized void initialHashes() {
        // for each project which has sources, hash them
        inputs.keySet().stream()
                .map(Input::getProject)
                .filter(Project::hasSourcesMapped)
                .distinct()
                .forEach(project -> {
                    Map<Path, DiskCache.CacheEntry> hashes = project.getSourceRoots().stream()
                            .map(Paths::get)
                            .map(DiskCache::hashContents)
                            .flatMap(Collection::stream)
                            .collect(Collectors.toMap(
                                    DiskCache.CacheEntry::getSourcePath,
                                    Function.identity(),
                                    (a, b) -> {
                                        throw new IllegalStateException("Two paths in a project had the same file " + a + ", " + b);
                                    }
                            ));
                    triggerChanges(project, hashes, Collections.emptyMap(), Collections.emptySet());
                });
    }

    /**
     * Marks that a file has been created, deleted, or modified in the given project.
     */
    public synchronized void triggerChanges(Project project, Map<Path, DiskCache.CacheEntry> createdFiles, Map<Path, DiskCache.CacheEntry> changedFiles, Set<Path> deletedFiles) {
        Map<Path, DiskCache.CacheEntry> hashes = currentProjectSourceHash.computeIfAbsent(project, ignore -> new HashMap<>());
        hashes.keySet().removeAll(deletedFiles);
        assert hashes.keySet().stream().noneMatch(createdFiles.keySet()::contains) : "File already exists, can't be added " + createdFiles.keySet() + ", " + hashes.keySet();
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
        Stream.concat(inputs.keySet().stream(), inputs.values().stream().flatMap(i -> i.getInputs().stream()))
                .filter(i -> i.getProject().hasSourcesMapped())
                .filter(i -> i.getOutputType().equals(OutputTypes.INPUT_SOURCES))
                .forEach(i -> {
                    Map<Path, DiskCache.CacheEntry> currentHashes = currentProjectSourceHash.get(i.getProject());
                    i.setCurrentContents(new TaskOutput(currentHashes.values()));
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
