package com.vertispan.j2cl.build;

import com.vertispan.j2cl.build.impl.CollectedTaskInputs;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.Function;
import java.util.stream.Collectors;

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

    /**
     * Creates a scheduler to perform work as needed. Before any task is attempted, the
     * disk cache will be queried, and once the disk cache confirms that only this invocation
     * will be doing the work, it will be started using the provided executor service. Do
     * not block on the returned future from submit() within another thread running in that
     * same executor service instance.
     *
     * Caller is responsible for shutting down the executor service - canceling ongoing work
     * should be supported, but presently isn't.
     *
     * @param executor
     * @param diskCache
     */
    public TaskScheduler(Executor executor, DiskCache diskCache) {
        this.executor = executor;
        this.diskCache = diskCache;
    }

    /**
     *
     * Params need to specify dependencies so we can track them internally, and when submitted
     */
    public CompletableFuture<Void> submit(Collection<CollectedTaskInputs> inputs) {
        // Build an initial set of work that doesn't need doing, we'll add to this as we go
        // We aren't concerned about missing filtered instances here
        Set<Input> ready = inputs.stream()
                .map(CollectedTaskInputs::getInputs)
                .flatMap(Collection::stream)
                .filter(i -> i.getOutputType().equals(OutputTypes.INPUT_SOURCES))
                .collect(Collectors.toCollection(HashSet::new));

        // Tracks all inputs by their general project+task, so we can inform all at once when real data is available.
        // In theory this could be done by sharing details between parent/child Input instances, probably should
        // be updated in the future
        Map<Input, List<Input>> allInputs = inputs.stream()
                .map(CollectedTaskInputs::getInputs)
                .flatMap(Collection::stream)
                .collect(Collectors.groupingBy(Function.identity()));

        Set<CollectedTaskInputs> remainingWork = new HashSet<>(inputs);
        CompletableFuture<Void> result = new CompletableFuture<>();

        scheduleAvailableWork(ready, allInputs, remainingWork, result);

        return result;
    }

    private void scheduleAvailableWork(Set<Input> ready, Map<Input, List<Input>> allInputs, Set<CollectedTaskInputs> remainingWork, CompletableFuture<Void> result) {
        // sort based on work which has no dependencies in this batch
        List<CollectedTaskInputs> canBeBuilt = remainingWork.stream()
                .filter(collectedTaskInputs -> ready.containsAll(collectedTaskInputs.getInputs()))
                .collect(Collectors.toList());

        // for each thing which has no unmet dependencies, ask disk cache if it needs doing
        canBeBuilt.forEach(taskDetails -> {
            diskCache.waitForTask(taskDetails).whenComplete((cacheResult, failure) -> {
                if (failure != null) {
                    // Give up now, something catastrophic happened
                    result.completeExceptionally(failure);
                    return;
                }

                // if succeeded amd we didn't do it ourselves, continue, schedule more work
                if (cacheResult.status() == DiskCache.Status.SUCCESS) {
                    // when something finishes, remove it from the various dependency lists and see if we can run the loop again with more work
                    remainingWork.remove(taskDetails);

                    for (Input input : allInputs.get(new Input(taskDetails.getProject(), taskDetails.getTaskFactory().getOutputType()))) {
                        //TODO this is very wrong, if an existing task is still working, it might now have the wrong inputs
                        input.setCurrentContents(cacheResult.output());
                    }

                    scheduleAvailableWork(ready, allInputs, remainingWork, result);
                    return;
                }

                // if failed, give up (stop existing work? probably not until we have a new call)
                if (cacheResult.status() == DiskCache.Status.FAILED) {
                    //TODO stop any future work, try to cancel existing
                    //TODO better logs, better message
                    result.completeExceptionally(new IllegalStateException("Failed, see logs"));
                    return;
                }

                // if we now have the lock, submit the work (only allow one {project,task} to run at a time, do this by locking on the Task instance)
                if (cacheResult.status() == DiskCache.Status.NOT_STARTED) {
                    // we can now begin this work off-thread, will be woke up when it finishes
                    executor.execute(() -> execute(taskDetails, cacheResult));
                    //return;
                }
            });
        });

        if (remainingWork.isEmpty()) {
            // no work left, mark as finished
            result.complete(null);
        }
    }

    private void execute(CollectedTaskInputs taskDetails, DiskCache.CacheResult result) {
        // TODO find a better way to do this lock, preventing more than one build on a single {project,task}
        // Only run a given task one at a time, since each needs their Input instances to resolve to a single
        // thing, it will be confusing if those change while running. An alternative could be that each Input
        // uses a threadlocal to use its own particularly resolved inputs
        synchronized (taskDetails) {
            // TODO move input population here

            // all inputs are populated, and it already has the config, we just need to start it up
            // with its output path and capture logs
            // TODO implement logs!
            try {
                taskDetails.getTask().execute(result.outputDir());
                result.markSuccess();
            } catch (Exception exception) {
                result.markFailure();
            }
        }
    }
}
