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

import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;

public class BlockingBuildListener implements BuildListener {
    private final CountDownLatch latch = new CountDownLatch(1);
    private Throwable throwable;
    private boolean success;

    @Override
    public void onSuccess() {
        success = true;
        latch.countDown();
    }

    @Override
    public void onFailure() {
        success = false;
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

    public boolean isSuccess() {
        if (latch.getCount() != 0) {
            throw new IllegalStateException("Can't call until finished");
        }
        return success;
    }
}
