package com.vertispan.j2cl.build;

import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;

public class BlockingBuildListener implements BuildListener {
    private final CountDownLatch latch = new CountDownLatch(1);
    private Throwable throwable;
    private boolean success;

    @Override
    public void onSuccess() {
        System.out.println("success");
        new Exception().printStackTrace();
        success = true;
        latch.countDown();
    }

    @Override
    public void onFailure() {
        System.out.println("failure");
        success = false;
        latch.countDown();
    }

    @Override
    public void onError(Throwable throwable) {
        System.out.println("error");
        this.throwable = throwable;
        latch.countDown();
    }

    public void blockUntilFinished() throws InterruptedException {
        latch.await();
        if (throwable != null) {
            throw new CompletionException(throwable);
        }
    }

    public boolean isSuccess() {
        if (latch.getCount() != 0) {
            throw new IllegalStateException("Can't call until finished");
        }
        return success;
    }
}
