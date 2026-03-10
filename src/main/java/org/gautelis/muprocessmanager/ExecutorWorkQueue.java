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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Adapter that exposes a {@link WorkQueue} backed by a {@link ThreadPoolExecutor}.
 */
public class ExecutorWorkQueue implements WorkQueue {
    private static final Logger log = LoggerFactory.getLogger(ExecutorWorkQueue.class);

    private static final long KEEP_ALIVE_MILLIS = 0L;
    private static final TimeUnit KEEP_ALIVE_UNIT = TimeUnit.MILLISECONDS;
    private static final String DEFAULT_THREAD_NAME_PREFIX = "org.gautelis.muprocessmanager.executor";

    private final ThreadPoolExecutor executor;

    public ExecutorWorkQueue(int nThreads) {
        this(nThreads, DEFAULT_THREAD_NAME_PREFIX);
    }

    public ExecutorWorkQueue(int nThreads, String threadNamePrefix) {
        Objects.requireNonNull(threadNamePrefix, "threadNamePrefix");
        BlockingQueue<Runnable> queue = new LinkedBlockingQueue<>();
        executor = new LoggingThreadPoolExecutor(
                nThreads,
                nThreads,
                KEEP_ALIVE_MILLIS,
                KEEP_ALIVE_UNIT,
                queue,
                new NamedThreadFactory(threadNamePrefix)
        );
    }

    @Override
    public void start() {
        executor.prestartAllCoreThreads();
        log.trace("Starting executor work queue...");
    }

    @Override
    public void stop() {
        log.trace("Stopping executor work queue...");
        executor.shutdownNow();
        log.trace("Executor work queue stopped");
    }

    @Override
    public boolean execute(Runnable t) {
        Objects.requireNonNull(t, "t");
        if (executor.isShutdown()) {
            return false;
        }
        try {
            executor.execute(t);
            return true;
        } catch (RejectedExecutionException ex) {
            log.warn("Rejected work queue task: {}", ex.getMessage());
            return false;
        }
    }

    @Override
    public boolean isEmpty() {
        return executor.getQueue().isEmpty();
    }

    @Override
    public long size() {
        return executor.getQueue().size();
    }

    private static final class NamedThreadFactory implements ThreadFactory {
        private final String prefix;
        private final AtomicInteger counter = new AtomicInteger(0);

        private NamedThreadFactory(String prefix) {
            this.prefix = prefix;
        }

        @Override
        public Thread newThread(Runnable r) {
            Thread thread = new Thread(r);
            thread.setName(prefix + "-" + counter.incrementAndGet());
            return thread;
        }
    }

    private static final class LoggingThreadPoolExecutor extends ThreadPoolExecutor {
        private LoggingThreadPoolExecutor(
                int corePoolSize,
                int maximumPoolSize,
                long keepAliveTime,
                TimeUnit unit,
                BlockingQueue<Runnable> workQueue,
                ThreadFactory threadFactory
        ) {
            super(corePoolSize, maximumPoolSize, keepAliveTime, unit, workQueue, threadFactory);
        }

        @Override
        protected void afterExecute(Runnable r, Throwable t) {
            super.afterExecute(r, t);
            if (t != null) {
                log.warn("Failed to run queued task: {}", t.getMessage(), t);
            }
        }
    }
}
