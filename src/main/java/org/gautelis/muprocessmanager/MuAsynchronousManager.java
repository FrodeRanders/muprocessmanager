/*
 * Copyright (C) 2017-2021 Frode Randers
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

/**
 * This is the asynchronous functionality of a micro-process manager, taking care of
 * background activities.
 */
public interface MuAsynchronousManager {
    /**
     * Starts the micro process manager asynchronous background tasks, i.e. initiates the
     * background tasks associated with detecting stuck processes and (re-)compensating
     * process tasks if the process has died.
     * <p>
     * If you need multiple instances of MuProcessManager, at the moment you should only start
     * the asynchronous background task in one single instance.
     * <p>
     * Also initiates the statistics logging (in the background).
     */
    void start();

    /**
     * Stops the micro process manager asynchronous background tasks.
     * <p>
     * As long as these tasks are running, the program will not exit.
     */
    void stop();
}
