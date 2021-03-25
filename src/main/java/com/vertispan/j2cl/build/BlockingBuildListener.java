package com.vertispan.j2cl.build;

import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;

public class BlockingBuildListener implements BuildListener {
    private final CountDownLatch latch = new CountDownLatch(1);
    private Throwable throwable;

    @Override
    public void onSuccess() {
        latch.countDown();
    }

    @Override
    public void onFailure() {
        latch.countDown();
    }

    @Override
    public void onError(Throwable throwable) {
        this.throwable = throwable;
        latch.countDown();
    }

    public void blockUntilFinished() throws InterruptedException {
        latch.await();
        if (throwable != null) {
            throw new CompletionException(throwable);
        }
    }
}
