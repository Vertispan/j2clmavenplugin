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

import com.google.gson.Gson;
import com.vertispan.j2cl.build.impl.CollectedTaskInputs;
import com.vertispan.j2cl.build.task.BuildLog;
import com.vertispan.j2cl.build.task.OutputTypes;
import com.vertispan.j2cl.build.task.TaskFactory;
import com.vertispan.j2cl.build.task.TaskContext;

import java.io.FileNotFoundException;
import java.io.FileReader;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.stream.Collectors;

import static com.vertispan.j2cl.build.task.ChangedCachedPath.ChangeType.ADDED;
import static com.vertispan.j2cl.build.task.ChangedCachedPath.ChangeType.MODIFIED;
import static com.vertispan.j2cl.build.task.ChangedCachedPath.ChangeType.REMOVED;

/**
 * Decides how much work to do, and when. Naive implementation just has a threadpool and does as much work
 * as possible at a time, depth first from the tree of tasks. A smarter impl could try to do work with many
 * dependents as soon as possible to increase parallelism, etc.
 *
 * The API here is that the scheduler is only called after the cache has been consulted, so it can be told
 * if a given unit of work is already complete.
 */
public class TaskScheduler {
    private final Executor executor;
    private final DiskCache diskCache;
    private final LocalProjectBuildCache buildCache;
    private final BuildLog buildLog;

    // This is technically incorrect, but covers the current use cases, and should fail loudly if this assumption
    // ends up being violated, so we know what to fix. This should be null upon submit(), set to the path of the
    // CacheResult when a final-task begins, and nulled out again when the final-task ends. If a final-task attempts
    // to start and this isn't null, we assert it is already set to this value (and skip the work), and assert it is
    // null when submitting any work.
    private final AtomicReference<String> finalTaskMarker = new AtomicReference<>();

    /**
     * Creates a scheduler to perform work as needed. Before any task is attempted, the
     * disk cache will be queried, and once the disk cache confirms that only this invocation
     * will be doing the work, it will be started using the provided executor service. Do
     * not block on the returned future from submit() within another thread running in that
     * same executor service instance.
     *
     * Caller is responsible for shutting down the executor service - canceling ongoing work
     * should be supported, but presently isn't.
     *  @param executor executor to submit work to, to be performed off thread
     * @param diskCache cache to read results from, and save new results to
     * @param buildCache
     * @param buildLog log to write details to about work being performed
     */
    public TaskScheduler(Executor executor, DiskCache diskCache, LocalProjectBuildCache buildCache, BuildLog buildLog) {
        this.executor = executor;
        this.diskCache = diskCache;
        this.buildCache = buildCache;
        this.buildLog = buildLog;
    }

    /**
     * Wraps the tasks that the scheduler is currently responsible for, representing the state of a single call to
     * submit().
     *
     * Future refactors should probably move more methods into this class, and possibly make it a top level type.
     */
    private static class Tasks {
        enum TaskState { PENDING, RUNNING, COMPLETE, CANCELED; }
        private final Map<CollectedTaskInputs, TaskState> work = new ConcurrentHashMap<>();
        private final AtomicBoolean isCanceled = new AtomicBoolean(false);

        public Tasks(Collection<CollectedTaskInputs> inputs, Set<Input> ready) {
            inputs.forEach(i -> work.put(i, ready.contains(i.getAsInput()) ? TaskState.COMPLETE : TaskState.PENDING));
        }

        public void dumpDebugState(BuildLog buildLog) {
            Set<CollectedTaskInputs> remaining = work.entrySet().stream()
                    .filter(e -> e.getValue() != TaskState.PENDING && e.getValue() != TaskState.RUNNING)
                    .map(Map.Entry::getKey)
                    .collect(Collectors.toSet());
            if (remaining.size() == 1) {
                buildLog.debug("Remaining work: task " + remaining.iterator().next().getDebugName());
            } else {
                buildLog.debug("Remaining work: " + remaining.size() + " tasks");
            }
        }

        public List<CollectedTaskInputs> pendingList() {
            if (isCanceled.get()) {
                return Collections.emptyList();
            }
            return work.entrySet().stream()
                    .filter(e -> e.getValue() == TaskState.PENDING)
                    .map(Map.Entry::getKey)
                    .collect(Collectors.toUnmodifiableList());
        }

        public void cancelPending() {
            isCanceled.set(true);
        }

        public boolean complete(CollectedTaskInputs input) {
            return work.put(input, TaskState.COMPLETE) != TaskState.COMPLETE;
        }

        public boolean isDone() {
            return work.values().stream().noneMatch(s -> (s == TaskState.PENDING && !isCanceled.get()) || s == TaskState.RUNNING);
        }
    }

    /**
     * Params need to specify dependencies so we can track them internally, and when submitted
     */
    public Cancelable submit(Collection<CollectedTaskInputs> inputs, BuildListener listener) {
        verifyFinalTaskMarkerNull();
        // Build an initial set of work that doesn't need doing, we'll add to this as we go
        // We aren't concerned about missing filtered instances here
        Set<Input> ready = inputs.stream()
                .map(CollectedTaskInputs::getInputs)
                .flatMap(Collection::stream)
                // "jar" is an internal type right now
                .filter(i -> i.getOutputType().equals(OutputTypes.INPUT_SOURCES) || i.getOutputType().equals("jar"))
                .collect(Collectors.toCollection(HashSet::new));

        // Tracks all inputs by their general project+task, so we can inform all at once when real data is available.
        // In theory this could be done by sharing details between parent/child Input instances, probably should
        // be updated in the future
        Map<Input, List<Input>> allInputs = inputs.stream()
                .map(CollectedTaskInputs::getInputs)
                .flatMap(Collection::stream)
                .collect(Collectors.groupingBy(Function.identity()));

        Tasks tasks = new Tasks(inputs, ready);

        scheduleAvailableWork(Collections.synchronizedSet(ready), allInputs, tasks, new BuildListener() {
            private final AtomicBoolean firstNotificationSent = new AtomicBoolean(false);
            @Override
            public void onSuccess() {
                if (firstNotificationSent.compareAndSet(false, true)) {
                    verifyFinalTaskMarkerNull();
                    listener.onSuccess();
                }
            }

            @Override
            public void onFailure() {
                if (firstNotificationSent.compareAndSet(false, true)) {
                    verifyFinalTaskMarkerNull();
                    listener.onFailure();
                }
            }

            @Override
            public void onError(Throwable throwable) {
                if (firstNotificationSent.compareAndSet(false, true)) {
                    verifyFinalTaskMarkerNull();
                    listener.onError(throwable);
                }
            }
        });

        return tasks::cancelPending;//TODO either this method or this lambda should check if there are no tasks running and trigger onSuccess
    }

    private void verifyFinalTaskMarkerNull() {
        String marker = finalTaskMarker.get();
        if (marker != null) {
            throw new IllegalStateException("Expected final task marker to be null - builds running concurrently? " + marker);
        }
    }

    private void scheduleAvailableWork(Set<Input> ready, Map<Input, List<Input>> allInputs, Tasks tasks, BuildListener listener) {
        tasks.dumpDebugState(buildLog);

        if (tasks.isDone()) {
            // no work left, mark entire set of tasks as finished
            listener.onSuccess();
            return;
        }
        // Filter based on work which has no currently pending dependencies
        tasks.pendingList().forEach(taskDetails -> {
            synchronized (ready) {
                if (ready.contains(taskDetails.getAsInput())) {
                    // work is already done
                    //TODO avoid getting into a situation where this gets hit so much
                    return;
                }
                if (!ready.containsAll(taskDetails.getInputs())) {
                    // at least one dependency isn't ready, move on, this will be called again when that changes
                    return;
                }
            }

            // check to see if this task is finished (or failed), or can be built by us now
            diskCache.waitForTask(taskDetails, new DiskCache.Listener() {
                private void executeTask(CollectedTaskInputs taskDetails, DiskCache.CacheResult result, BuildListener listener) {
                    // all inputs are populated, and it already has the config, we just need to start it up
                    // with its output path and capture logs
                    buildLog.info("Starting " + taskDetails.getDebugName());
                    buildLog.debug("Task " + taskDetails.getDebugName() + " has " + taskDetails.getInputs().size() + " inputs");
                    TaskBuildLog log;
                    try {
                        log = new TaskBuildLog(buildLog, taskDetails.getDebugName(), result.logFile());
                    } catch (FileNotFoundException e) {
                        // Can't proceed without being able to write to disk, just shut down
                        listener.onError(e);
                        throw new RuntimeException(e);
                    }
                    try {
                        long start = System.currentTimeMillis();

                        Optional<DiskCache.CacheResult> latestResult = buildCache.getLatestResult(taskDetails.getProject(), taskDetails.getTaskFactory().getOutputType());
                        final TaskSummaryDiskFormat taskSummaryDiskFormat = latestResult.map(TaskScheduler.this::getTaskSummary).orElse(null);

                        if (taskSummaryDiskFormat == null) {
                            latestResult = Optional.empty();
                        }


                        // Update any existing input to reflect what has changed
                        if (latestResult.isPresent()) {
                            for (TaskSummaryDiskFormat.InputDiskFormat onDiskInput : taskSummaryDiskFormat.getInputs()) {
                                // if this input is not present any more, we cannot build incrementally
                                if (taskDetails.getInputs().stream().noneMatch(currentInput ->
                                        currentInput.getProject().getKey().equals(onDiskInput.getProjectKey())
                                                && currentInput.getOutputType().equals(onDiskInput.getOutputType())
                                )) {
                                    latestResult = Optional.empty();
                                }
                            }
                            for (Input input : taskDetails.getInputs()) {
                                input.setBuildSpecificChanges(() -> {
                                    Optional<TaskSummaryDiskFormat.InputDiskFormat> prevInput = taskSummaryDiskFormat.getInputs().stream()
                                            .filter(i -> i.getProjectKey().equals(input.getProject().getKey()))
                                            .filter(i -> i.getOutputType().equals(input.getOutputType()))
                                            .findAny();
                                    if (prevInput.isPresent()) {
                                        return diff(input.getFilesAndHashes().stream().collect(Collectors.toMap(e -> e.getSourcePath().toString(), Function.identity())), prevInput.get().getFileHashes());
                                    }

                                    return input.getFilesAndHashes().stream()
                                            .map(entry -> new ChangedCachedPath(ADDED, entry.getSourcePath(), entry)).collect(Collectors.toUnmodifiableList());
                                });
                            }
                        } else {
                            for (Input input : taskDetails.getInputs()) {
                                input.setBuildSpecificChanges(() ->
                                        input.getFilesAndHashes().stream()
                                                .map(entry -> new ChangedCachedPath(ADDED, entry.getSourcePath(), entry))
                                                .collect(Collectors.toUnmodifiableList())
                                );
                            }
                        }

                        taskDetails.getTask().execute(new TaskContext(result.outputDir(), log, latestResult.map(DiskCache.CacheResult::outputDir).orElse(null)));
                        if (Thread.currentThread().isInterrupted()) {
                            // Tried and failed to be canceled, so even though we were successful, some files might
                            // have been deleted. Continue deleting contents
                            result.cancel();
                            return;
                        }
                        long elapsedMillis = System.currentTimeMillis() - start;
                        if (elapsedMillis > 5) {
                            buildLog.info("Finished " + taskDetails.getDebugName() + " in " + elapsedMillis + "ms");
                        }
                        buildCache.markLocalSuccess(taskDetails.getProject(), taskDetails.getTaskFactory().getOutputType(), result.taskDir());
                        result.markSuccess();

                    } catch (Throwable exception) {
                        if (Thread.currentThread().isInterrupted()) {
                            // Tried and failed to be canceled, so even though we failed, some files might have
                            // been deleted. Continue deleting contents.
                            result.cancel();
                            return;
                        }
                        buildLog.error("Exception executing task " + taskDetails.getDebugName(), exception);
                        result.markFailure();
                        listener.onFailure();
                        throw new RuntimeException(exception);// don't safely return, we don't want to continue
                    }

                    // if this is a final task, execute it
                    if (taskDetails.getTask() instanceof TaskFactory.FinalOutputTask) {
                        boolean finished;
                        try {
                            // if this fails, we'll report failure to the listener
                            finished = executeFinalTask(taskDetails, result);
                        } catch (Exception exception) {
                            // TODO can't proceed, shut everything down
                            listener.onError(exception);
                            throw new RuntimeException(exception);
                        }
                        if (finished) {
                            scheduleMoreWork(result);
                        }
                    } else {
                        // look for more work now that we've finished this one
                        scheduleMoreWork(result);
                    }
                }

                @Override
                public void onReady(DiskCache.CacheResult cacheResult) {
                    // We can now begin this work off-thread, will be woken up when it finishes.
                    // It is too late to cancel at this time, so no need to check.
                    cacheResult.markBegun();
                    executor.execute(() -> {
                        executeTask(taskDetails, cacheResult, listener);
                    });
                }

                @Override
                public void onFailure(DiskCache.CacheResult cacheResult) {
                    //TODO stop any future work, try to cancel existing
                    //TODO better logs, better message
                    listener.onFailure();
                }

                @Override
                public void onError(Throwable throwable) {
                    //TODO can't proceed, shut things down - not just stopping the CF, but everything
                    listener.onError(throwable);
                }

                @Override
                public void onSuccess(DiskCache.CacheResult cacheResult) {
                    // Succeeded, didn't do it ourselves, can schedule more work unless there is a final task
                    if (taskDetails.getTask() instanceof TaskFactory.FinalOutputTask) {
                        // Do the work in an executor, so that we don't block the current thread (usually main or disk cache watcher)
                        // First though, we'll inline scheduleMoreWork so that we don't attempt to do this task a second time
                        // NOTE: we are not calling setCurrentContents on this, since no task may depend on this
                        ready.add(taskDetails.getAsInput());
                        executor.execute(() -> {
                            boolean finished;
                            try {
                                // if this fails, we'll report failure to the listener
                                finished = executeFinalTask(taskDetails, cacheResult);
                            } catch (Exception exception) {
                                // TODO can't proceed, shut everything down
                                listener.onError(exception);
                                throw new RuntimeException(exception);
                            }

                            if (finished) {
                                // we have to schedule more work afterwards because this is what triggers "all done" at the end,
                                // though it is likely that there isn't any more to do, since we just did the final output work
                                scheduleMoreWork(cacheResult);
                            }
                        });
                    } else {
                        scheduleMoreWork(cacheResult);
                    }
                }

                /**
                 * Marks the currently running task as complete, registers its output to be available for future
                 * tasks as inputs, and signals that more work can begin based on this change.
                 * @param cacheResult the newly finished output
                 */
                private void scheduleMoreWork(DiskCache.CacheResult cacheResult) {
                    boolean scheduleMore = false;
                    // mark current item as ready
                    synchronized (ready) {
                        ready.add(taskDetails.getAsInput());

                        // When something finishes, remove it from the various dependency lists and see if we can run the loop again with more work.
                        // Presently this could be called multiple times, so we check if already removed
                        if (tasks.complete(taskDetails)) {
                            TaskOutput output = cacheResult.output();
                            for (Input input : allInputs.computeIfAbsent(taskDetails.getAsInput(), ignore -> Collections.emptyList())) {
                                // since we don't support running more than one thing at a time, this will not change data out from under a running task
                                input.setCurrentContents(output);
                                //TODO This maybe can race with the check on line 84, and we break since the input isn't ready yet
                            }

                            scheduleMore = true;
                        }
                    }
                    if (scheduleMore) {
                        scheduleAvailableWork(ready, allInputs, tasks, listener);
                    }
                }

                private boolean executeFinalTask(CollectedTaskInputs taskDetails, DiskCache.CacheResult cacheResult) throws Exception {
                    if (!finalTaskMarker.compareAndSet(null, cacheResult.outputDir().toString())) {
                        // failed to set it to null, some other thread already has the lock
                        buildLog.info("skipping final task, some other thread has the lock");
                        return false;
                    }
                    buildLog.info("Starting final task " + taskDetails.getDebugName());
                    long start = System.currentTimeMillis();
                    try {
                        //TODO Make sure that we want to write this to _only_ the current log, and not also to any file
                        //TODO Also be sure to write a prefix automatically

                        // TODO also consider if lastSuccessfulPath should be null for final tasks
                        ((TaskFactory.FinalOutputTask) taskDetails.getTask()).finish(new TaskContext(cacheResult.outputDir(), buildLog, null));
                        buildLog.info("Finished final task " + taskDetails.getDebugName() + " in " + (System.currentTimeMillis() - start) + "ms");
                    } catch (Throwable t) {
                        buildLog.error("FAILED   " + taskDetails.getDebugName() + " in " + (System.currentTimeMillis() - start) + "ms",t);
                        throw t;
                    } finally {
                        String previous = finalTaskMarker.getAndSet(null);
                        if (!previous.equals(cacheResult.outputDir().toString())) {
                            //noinspection ThrowFromFinallyBlock
                            throw new AssertionError("final task marker should have been " + cacheResult.outputDir() + ", instead was " + previous);
                        }
                    }
                    return true;
                }
            });
        });
    }

    private List<ChangedCachedPath> diff(Map<String, DiskCache.CacheEntry> currentFiles, Map<String, String> previousFiles) {
        List<ChangedCachedPath> changes = new ArrayList<>();
        Set<String> added = new HashSet<>(currentFiles.keySet());
        added.removeAll(previousFiles.keySet());
        added.forEach(newPath -> {
            changes.add(new ChangedCachedPath(ADDED, Paths.get(newPath), currentFiles.get(newPath)));
        });

        Set<String> removed = new HashSet<>(previousFiles.keySet());
        removed.removeAll(currentFiles.keySet());
        removed.forEach(removedPath -> {
            changes.add(new ChangedCachedPath(REMOVED, Paths.get(removedPath), null));
        });

        Map<String, DiskCache.CacheEntry> changed = new HashMap<>(currentFiles);
        changed.keySet().removeAll(added);
        changed.forEach((possiblyModifiedPath, entry) -> {
            if (!entry.getHash().asString().equals(previousFiles.get(possiblyModifiedPath))) {
                changes.add(new ChangedCachedPath(MODIFIED, Paths.get(possiblyModifiedPath), entry));
            }
        });

        return changes;
    }

    private TaskSummaryDiskFormat getTaskSummary(DiskCache.CacheResult latestResult) {
        try {
            return new Gson().fromJson(new FileReader(latestResult.cachedSummary().toFile()), TaskSummaryDiskFormat.class);
        } catch (FileNotFoundException ex) {
            return null;
        }
    }
}
