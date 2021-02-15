package com.vertispan.j2cl.build;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.WatchService;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Manages the cached task inputs and outputs.
 */
public abstract class DiskCache {
    public enum Status {
        NOT_STARTED, RUNNING, FAILED, SUCCESS;
    }

    private final WatchService service;
    private ExecutorService executorService = Executors.newSingleThreadExecutor();
    public DiskCache(File cacheDir) {

        //TODO replace with something less slow on mac
        service = cacheDir.toPath().getFileSystem().newWatchService();

    }

    protected abstract File successMarker(Path taskDir);
    protected abstract File failureMarker(Path taskDir);

    /**
     * Provides a string key to use to represent this task's inputs. A directory will be created based on this
     * and the output of the task will be stored in it.
     * @param factory the factory that created the task, so that different factories don't share cache output
     * @param task the task instance itself, probably not necessary
     * @param configKeys the keys consumed by the tasks, along with their values
     * @param inputs the inputs from other tasks that will be used
     * @return a string key representing these values
     */
    public String getCacheKey(TaskFactory factory, TaskFactory.Task task, Map<String, String> configKeys, List<Input> inputs) {

    }

    /**
     * Atomically starts the task, or returns the current status if not able to.
     * @param factory
     * @param configs
     * @param inputs
     * @return
     */
    public Status tryStartTask(TaskFactory factory, Map<String, String> configs, List<Input> inputs) throws IOException {
        Path taskDir = null;//TODO
        // first check if it is already on disk
        if (!taskDir.getParent().toFile().exists()) {
            Files.createDirectories(taskDir.getParent());
        }

        // try to create the task directory - if we succeed, we own it (this is atomic), if we tail, someone else already made it and we wait for them to finish
        if (!taskDir.toFile().mkdir()) {
            // we'll have to wait for complete/failed markers to see what is happening if it is pending
            // if either already exists, we notify that result, otherwise it is assumed to be running

            if (successMarker(taskDir).exists()) {
                // consumer can read the log if they want to see already-success
                return Status.SUCCESS;
            }
            if (failureMarker(taskDir).exists()) {
                // consumer can read the other log
                // TODO make sure it is only written out once per build?
                return Status.FAILED;
            }
            return Status.RUNNING;
        }

        return Status.NOT_STARTED;
    }

    public CompletableFuture<Status> waitForFinish(TaskFactory factory, Map<String, String> configs, List<Input> inputs) {
        // add us to the existing watchservice (creating if necessary)

    }

    /**
     * Returns the full log of the task after it is completed.
     * @param factory
     * @param configs
     * @param inputs
     * @return
     */
    public String getLog(TaskFactory factory, Map<String, String> configs, List<Input> inputs) {
        throw new UnsupportedOperationException("getLog");
    }

}
