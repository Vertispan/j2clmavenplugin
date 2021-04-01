package com.vertispan.j2cl.build;

import com.vertispan.j2cl.build.impl.CollectedTaskInputs;
import com.vertispan.j2cl.build.task.OutputTypes;
import com.vertispan.j2cl.build.task.TaskFactory;

import java.util.*;
import java.util.concurrent.Executor;
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
     * Params need to specify dependencies so we can track them internally, and when submitted
     */
    public Cancelable submit(Collection<CollectedTaskInputs> inputs, BuildListener listener) {
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

        Set<CollectedTaskInputs> remainingWork = Collections.synchronizedSet(new HashSet<>(inputs));

        scheduleAvailableWork(ready, allInputs, remainingWork, listener);

        return remainingWork::clear;
    }

    private void scheduleAvailableWork(Set<Input> ready, Map<Input, List<Input>> allInputs, Set<CollectedTaskInputs> remainingWork, BuildListener listener) {
        // Filter based on work which has no dependencies in this batch.
        // (synchronized copy to avoid CME)
        List<CollectedTaskInputs> copy = Arrays.asList(remainingWork.toArray(new CollectedTaskInputs[0]));

        // iterating a copy in case something is removed while we're in here - at the time we were called it was important
        copy.forEach(taskDetails -> {
            if (!ready.containsAll(taskDetails.getInputs())) {
                // at least one dependency isn't ready, move on
                return;
            }

            // check to see if this task is finished (or failed), or can be built by us now
            diskCache.waitForTask(taskDetails, new DiskCache.Listener() {
                @Override
                public void onReady(DiskCache.CacheResult cacheResult) {
                    // We can now begin this work off-thread, will be woke up when it finishes.
                    // It is too late to cancel at this time, so no need to check.
                    executor.execute(() -> execute(taskDetails, cacheResult, listener));
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
                    // succeeded, didn't do it ourselves, can schedule more work unless there is a final task
                    if (taskDetails.getTask() instanceof TaskFactory.FinalOutputTask) {
                        // do the work in an executor, so that we don't block the current thread (usually main or disk cache watcher)
                        executor.execute(() -> {
                            ((TaskFactory.FinalOutputTask) taskDetails.getTask()).finish();
                            // we have to schedule more work afterwards because this is what triggers "all done" at the end
                            scheduleMoreWork(cacheResult);
                        });
                    } else {
                        scheduleMoreWork(cacheResult);
                    }

                }

                private void scheduleMoreWork(DiskCache.CacheResult cacheResult) {
                    // When something finishes, remove it from the various dependency lists and see if we can run the loop again with more work.
                    // Presently this could be called multiple times, so we check if already removed
                    if (remainingWork.remove(taskDetails)) {

                        for (Input input : allInputs.get(new Input(taskDetails.getProject(), taskDetails.getTaskFactory().getOutputType()))) {
                            //TODO this is very wrong, if an existing task is still working, it might now have the wrong inputs
                            input.setCurrentContents(cacheResult.output());
                        }

                        scheduleAvailableWork(ready, allInputs, remainingWork, listener);
                    }
                }
            });
        });

        if (remainingWork.isEmpty()) {
            // no work left, mark entire set of tasks as finished
            listener.onSuccess();
        }
    }

    private void execute(CollectedTaskInputs taskDetails, DiskCache.CacheResult result, BuildListener listener) {
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
