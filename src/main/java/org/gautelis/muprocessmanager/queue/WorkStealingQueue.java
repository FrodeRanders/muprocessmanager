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
package org.gautelis.muprocessmanager.queue;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.concurrent.BlockingDeque;
import java.util.concurrent.LinkedBlockingDeque;

/**
 * A work queue backed by a set of pool workers, internally
 * using multiple dequeues, where the pool workers steal work
 * from each other.
 */
public class WorkStealingQueue implements WorkQueue {
    private static final Logger log = LogManager.getLogger(WorkStealingQueue.class);

	private final int nThreads;
    private int queue_no = 0;
    private final PoolWorker[] threads;
    private final BlockingDeque[] queue;
    private volatile boolean stopRequested = false;
    
    private final Object lock = new Object();
    
    /* 
     * constructor to initiate worker threads and queue associated with it
     */
    /* package private */ WorkStealingQueue(int nThreads)
    {
        this.nThreads = nThreads;
        queue = new BlockingDeque[nThreads];
        threads = new PoolWorker[nThreads];
        for (int i=0; i<nThreads; i++) {
        	queue[i] = new LinkedBlockingDeque<Runnable>();
        }
    }
    
    private Runnable stealWork(int index) {
        /*
        // go through queues from index + 1 to (index + (nThreads - 1)) % nThreads
        for (int i=1 ; i<nThreads ; i++) {
            Object o = queues[(index + i) % nThreads].pollFirst();
            if(o!=null) {
                return (Runnable) o;
            }
        }
        */
    	for (int i=0; i<nThreads; i++) {
    		if (i != index) {
    			Object o = queue[i].pollFirst();
    			if (o!=null) {
    				return (Runnable) o;
    			}
    		}
    	}
    	
    	return null;
    	
    }
    
    public void start() {
    	for (int i=0; i<nThreads; i++) {
            threads[i] = new PoolWorker(i);
            threads[i].start();
        }

        if (log.isTraceEnabled()) {
            log.trace("Starting work queue...");
        }
    }

    public void stop() {
        if (log.isTraceEnabled()) {
            log.trace("Stopping work queue...");
        }

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
			queue[queue_no++ % nThreads].putFirst(r);
			if (queue_no == nThreads){
				queue_no = 0;
			}
            return true;
		} catch (InterruptedException e) {
            String info = "Failed to enqueue task: ";
            Throwable baseCause = org.gautelis.vopn.lang.Stacktrace.getBaseCause(e);
            info += baseCause.getMessage();
            log.warn(info, e);
		}
        return false;
    }

    /*
     * Checks whether queue is empty (or not)
     */
    public synchronized boolean isEmpty() {
        for (BlockingDeque q : queue) {
            if (!q.isEmpty()) {
                return false;
            }
        }
        return true;
    }

    /*
     * Clean-up the worker thread when all the tasks are done
     */
    private synchronized void doInterruptAllWaitingThreads() {
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
    	
    	private final int index;
    	
    	PoolWorker(int index) {
    		this.index = index;
    	}
    	
    	   	
    	/*
    	 * Method to retrieve task from worker queue and start executing it.
    	 * This thread will wait for a task if there is no task in the queue. 
    	 */
        public void run() {

            while (!stopRequested) {
                Runnable r = (Runnable) queue[index].pollLast();
				if (null == r) {
					r = stealWork(index);
					if (null == r) {
						// looks like there is no work to steal
                        try {
                            sleep(500); /* half a second */
                        }
                        catch (InterruptedException ignore) {
                        }
						continue; // and check if we are requested to stop
					}
				}

                // If we don't catch RuntimeException, 
                // the pool could leak threads
                try {
                    if (log.isTraceEnabled()) {
                        log.trace("Running pool worker [" + index + "] task");
                    }
                    r.run();
                }
                catch (java.lang.Throwable t) {
                    String info = "Failed to run queued task: ";
                    Throwable baseCause = org.gautelis.vopn.lang.Stacktrace.getBaseCause(t);
                    info += baseCause.getMessage();
                    log.info(info, t);
                }
            }
        }
    }
}
