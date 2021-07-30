package com.vertispan.j2cl.build;

/**
 * Provides notifications about the status of a build. May still be called after the build is canceled, as
 * all work is not instantly halted.
 */
public interface BuildListener {
    enum Activity {
        STARTED,
        SUCCEEDED,
        FAILED
    }
    /**
     * Notifies about the progress of the build. The sum of completedCount, startedCount, and pendingCount
     * is the total count of tasks.
     *
     * @param completedCount the number of finished tasks
     * @param startedCount the number of in progress tasks
     * @param pendingCount the number of unstarted tasks
     * @param task the task that just registered activity
     * @param project the project that just registered activity
     * @param activity the last activity
     */
    default void onProgress(int completedCount, int startedCount, int pendingCount, String task, Project project, Activity activity) {}

    void onSuccess();
    void onFailure();
    void onError(Throwable throwable);
}
