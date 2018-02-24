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

/**
 * Factory that creates different types of worker queues:
 * {@link SimpleWorkQueue}, {@link MultiWorkQueue}, {@link WorkStealingQueue}.
 */
public class WorkerQueueFactory {
    private static final Logger log = LogManager.getLogger(WorkerQueueFactory.class);

    public enum Type
    {
        Simple,
        Multi,
        WorkStealing
    }

    /**
     * Returns a thread-backed queue.
     * @param type {@link Type} of queue
     * @param nThreads number of threads tending to the queue
     * @return
     */
	public static WorkQueue getWorkQueue(Type type, int nThreads) {
		switch(type) {
			case Simple:
					return new SimpleWorkQueue(nThreads);
			
			case Multi:
					return new MultiWorkQueue(nThreads);

            case WorkStealing:
            default:
					return new WorkStealingQueue(nThreads);
		}
	}
}