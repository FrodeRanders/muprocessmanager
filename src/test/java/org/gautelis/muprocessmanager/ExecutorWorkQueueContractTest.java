/*
 * Copyright (C) 2017-2026 Frode Randers
 * All rights reserved
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */
package org.gautelis.muprocessmanager;

import org.gautelis.vopn.queue.WorkQueue;
import org.junit.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ExecutorWorkQueueContractTest {
    @Test
    public void testQueueRejectsTasksAfterStop() {
        ExecutorWorkQueue queue = new ExecutorWorkQueue(1);
        queue.start();
        queue.stop();

        AtomicInteger calls = new AtomicInteger();
        boolean accepted = queue.execute(calls::incrementAndGet);

        assertFalse(accepted);
        assertEquals(0L, queue.size());
        assertTrue(queue.isEmpty());
        assertEquals(0, calls.get());
    }

    @Test
    public void testQueueDropsQueuedWorkOnStop() throws Exception {
        ExecutorWorkQueue queue = new ExecutorWorkQueue(1);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);
        AtomicBoolean secondRan = new AtomicBoolean(false);

        queue.start();
        queue.execute(() -> {
            started.countDown();
            try {
                Thread.sleep(10_000L);
            }
            catch (InterruptedException expected) {
                interrupted.countDown();
                Thread.currentThread().interrupt();
            }
        });
        assertTrue(started.await(2, TimeUnit.SECONDS));

        queue.execute(() -> secondRan.set(true));
        assertEquals(1L, queue.size());

        queue.stop();

        assertTrue(interrupted.await(2, TimeUnit.SECONDS));
        assertEquals(0L, queue.size());
        assertTrue(queue.isEmpty());
        assertFalse(secondRan.get());
    }

    @Test
    public void testQueueContinuesAfterTaskFailure() throws Exception {
        withSuppressedUncaughtExceptions(() -> assertQueueSurvivesTaskFailure(new ExecutorWorkQueue(1)));
    }

    @Test
    public void testStopBeforeStartIsSafe() {
        ExecutorWorkQueue queue = new ExecutorWorkQueue(1);
        queue.stop();
        assertEquals(0L, queue.size());
        assertTrue(queue.isEmpty());
    }

    private static void assertQueueSurvivesTaskFailure(WorkQueue queue) throws Exception {
        CountDownLatch finished = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();

        queue.start();
        try {
            assertTrue(queue.execute(() -> {
                calls.incrementAndGet();
                throw new RuntimeException("boom");
            }));
            assertTrue(queue.execute(() -> {
                calls.incrementAndGet();
                finished.countDown();
            }));

            assertTrue(finished.await(2, TimeUnit.SECONDS));
            assertEquals(2, calls.get());
        }
        finally {
            queue.stop();
        }
    }

    private static void withSuppressedUncaughtExceptions(CheckedRunnable action) throws Exception {
        Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
            // Characterization tests intentionally provoke worker failure.
        });
        try {
            action.run();
        }
        finally {
            Thread.setDefaultUncaughtExceptionHandler(previous);
        }
    }

    @FunctionalInterface
    private interface CheckedRunnable {
        void run() throws Exception;
    }
}
