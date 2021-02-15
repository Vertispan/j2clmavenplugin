package com.vertispan.j2cl.build;

import java.util.concurrent.*;

/**
 * Decides how much work to do, and when. Naive implementation just has a threadpool and does as much work
 * as possible at a time, depth first from the tree of tasks. A smarter impl could try to do work with many
 * dependents as soon as possible to increase parallelism, etc.
 *
 * The API here is that the scheduler is only called after the cache has been consulted, so it can be told
 * if a given unit of work is already complete.
 */
public class TaskScheduler {
    private final ExecutorService executorService;
    private final CountDownLatch workRemaining = new CountDownLatch();

    public TaskScheduler(int maxThreads) {
        executorService = Executors.newFixedThreadPool(maxThreads);
    }

    /**
     *
     * Params need to specify dependencies so we can track them internally, and when submitted
     */
    public Future<Void> submit(/*...*/) {
        // sort based on work which has no dependencies in this batch

        // for each thing which has no unmet dependencies, ask disk cache if it needs doing

        // if already succeeded, continue

        // if already failed, give up (stop existing work? probably not until we have a new call)

        // if already started by someone else, pass a lambda so we know when it is done
        // optional: count down one thread so we don't fight with another process? i.e. take a thread to wait for the work?

        // if we now have the lock, submit the work

        // when we run out of work to enqueue, wait until something finishes
        // when we run out of threads, wait until something finishes

        // when something finishes, remove it from the various dependency lists and see if we can run the loop again with more work


        return CompletableFuture.completedFuture(null);
    }
}
