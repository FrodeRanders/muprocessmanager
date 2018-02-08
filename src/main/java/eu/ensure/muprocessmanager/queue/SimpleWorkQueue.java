/*
 * Copyright (C) 2017 Frode Randers
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
package eu.ensure.muprocessmanager.queue;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.concurrent.BlockingDeque;
import java.util.concurrent.LinkedBlockingDeque;

public class SimpleWorkQueue implements WorkQueue {
    private static final Logger log = LogManager.getLogger(SimpleWorkQueue.class);

    private final int nThreads;
    private final PoolWorker[] threads;
    private final BlockingDeque queue;
    private volatile boolean stopRequested = false;
    
    private final Object lock = new Object();
    
    /* 
     * constructor to initiate worker threads and queue associated with it
     */
    /* package private */ SimpleWorkQueue(int nThreads)
    {
        this.nThreads = nThreads;
        queue = new LinkedBlockingDeque<Runnable>();
        threads = new PoolWorker[nThreads];
    }
    
    public void start() {
    	for (int i=0; i<nThreads; i++) {
            threads[i] = new PoolWorker();
            threads[i].start();
        }

        if (log.isTraceEnabled()) {
            log.trace("Starting work queue...");
        }
    }

    public void stop() {
        log.info("Stopping work queue...");

        stopRequested = true;
        doInterruptAllWaitingThreads();

        if (log.isTraceEnabled()) {
            log.trace("Work queue stopped");
        }
    }

    /*
     * Executes the given task in the future.
     * Queues the task and notifies the waiting thread. Also it makes
     * the Work assigner to wait if the queued task reaches to threshold
     */
    @SuppressWarnings("unchecked")
    public boolean execute(Runnable r) {
    	try {
			queue.putFirst(r);
            return true;

		} catch (InterruptedException e) {
            String info = "Failed to enqueue task: ";
            Throwable baseCause = eu.ensure.vopn.lang.Stacktrace.getBaseCause(e);
            info += baseCause.getMessage();
            log.warn(info, e);
		}
        return false;
    }

    /*
     * Checks whether queue is empty (or not)
     */
    public synchronized boolean isEmpty() {
        return queue.isEmpty();
    }


    /*
     * Clean-up the worker thread when all the tasks are done
     */
    private void doInterruptAllWaitingThreads() {
    	//Interrupt all the threads
    	for (int i=0; i<nThreads; i++) {
    		threads[i].interrupt();
    	}
    	
    	synchronized(lock) {
    		lock.notify();
    	}
    }

    /*
     * Worker thread to execute user tasks
     */
    private class PoolWorker extends Thread {
    	/*
    	 * Method to retrieve task from worker queue and start executing it.
    	 * This thread will wait for a task if there is no task in the queue. 
    	 */
        public void run() {

            Runnable r;

            while (!stopRequested) {
            	try {
					r = (Runnable) queue.takeLast();
				}
                catch (InterruptedException e1) {
					continue; // and check if we are requested to stop
				}

                try {
                    if (log.isTraceEnabled()) {
                        log.trace("Running pool worker task");
                    }
                    r.run();
                }
                catch (java.lang.Throwable t) {
                    String info = "Failed to run queued task: ";
                    Throwable baseCause = eu.ensure.vopn.lang.Stacktrace.getBaseCause(t);
                    info += baseCause.getMessage();
                    log.info(info, t);
                }
            }
        }
    }
}
