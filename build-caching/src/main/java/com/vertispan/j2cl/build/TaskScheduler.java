package com.vertispan.j2cl.build;

import com.vertispan.j2cl.build.impl.CollectedTaskInputs;
import com.vertispan.j2cl.build.task.OutputTypes;
import com.vertispan.j2cl.build.task.TaskFactory;
import com.vertispan.j2cl.build.task.TaskOutput;

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
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

        Set<CollectedTaskInputs> remainingWork = Collections.synchronizedSet(new HashSet<>(inputs));
        remainingWork.removeIf(item -> ready.contains(item.getAsInput()));

        scheduleAvailableWork(Collections.synchronizedSet(ready), allInputs, remainingWork, new BuildListener() {
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

        return remainingWork::clear;
    }

    private void verifyFinalTaskMarkerNull() {
        String marker = finalTaskMarker.get();
        if (marker != null) {
            throw new IllegalStateException("Expected final task marker to be null - builds running concurrently? " + marker);
        }
    }

    private void scheduleAvailableWork(Set<Input> ready, Map<Input, List<Input>> allInputs, Set<CollectedTaskInputs> remainingWork, BuildListener listener) {
        if (remainingWork.isEmpty()) {
            // no work left, mark entire set of tasks as finished
            listener.onSuccess();
            return;
        }
        // Filter based on work which has no dependencies in this batch.
        // (synchronized copy to avoid CME)
        List<CollectedTaskInputs> copy = Arrays.asList(remainingWork.toArray(new CollectedTaskInputs[0]));


        // iterating a copy in case something is removed while we're in here - at the time we were called it was important
        copy.forEach(taskDetails -> {
            if (ready.contains(taskDetails.getAsInput())) {
                // work is already done
                //TODO avoid getting into a situation where this gets hit so much
                return;
            }
            synchronized (ready) {
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
                    // TODO implement logs!
                    try {
                        long start = System.currentTimeMillis();

                        taskDetails.getTask().execute(new TaskOutput(result.outputDir()));
                        long elapsedMillis = System.currentTimeMillis() - start;
                        if (elapsedMillis > 5) {
                            System.out.println(taskDetails.getProject().getKey() + " finished " + taskDetails.getTaskFactory().getOutputType() + " in " + elapsedMillis + "ms");
                        }
                        result.markSuccess();

                    } catch (Exception exception) {
                        exception.printStackTrace();//TODO once we log, don't do this
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
                    // We can now begin this work off-thread, will be woke up when it finishes.
                    // It is too late to cancel at this time, so no need to check.
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
                        if (remainingWork.remove(taskDetails)) {
                            for (Input input : allInputs.computeIfAbsent(taskDetails.getAsInput(), ignore -> Collections.emptyList())) {
                                // since we don't support running more than one thing at a time, this will not change data out from under a running task
                                input.setCurrentContents(cacheResult.output());
                                //TODO This maybe can race with the check on line 84, and we break since the input isn't ready yet
                            }

                            scheduleMore = true;
                        }
                    }
                    if (scheduleMore) {
                        scheduleAvailableWork(ready, allInputs, remainingWork, listener);
                    }
                }

                private boolean executeFinalTask(CollectedTaskInputs taskDetails, DiskCache.CacheResult cacheResult) throws Exception {
                    if (!finalTaskMarker.compareAndSet(null, cacheResult.outputDir().toString())) {
                        // failed to set it to null, some other thread already has the lock
                        System.out.println("skipping final task, some other thread has the lock");
                        return false;
                    }
                    System.out.println("starting final task " + taskDetails.getProject().getKey() + " " + taskDetails.getTaskFactory().getOutputType());
                    long start = System.currentTimeMillis();
                    try {
                        ((TaskFactory.FinalOutputTask) taskDetails.getTask()).finish(new TaskOutput(cacheResult.outputDir()));
                        System.out.println(taskDetails.getProject().getKey() + " final task " + taskDetails.getTaskFactory().getOutputType() + " finished in " + (System.currentTimeMillis() - start) + "ms");
                    } catch (Throwable t) {
                        System.out.println(taskDetails.getProject().getKey() + " final task " + taskDetails.getTaskFactory().getOutputType() + " failed in " + (System.currentTimeMillis() - start) + "ms");
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

}
