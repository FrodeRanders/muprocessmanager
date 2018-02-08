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
package test;

import eu.ensure.muprocessmanager.*;
import eu.ensure.muprocessmanager.queue.WorkQueue;
import eu.ensure.muprocessmanager.queue.WorkerQueueFactory;
import junit.framework.Test;
import junit.framework.TestCase;
import junit.framework.TestSuite;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Locale;
import java.util.UUID;

public class AppTest extends TestCase {
    private static final Logger log = LogManager.getLogger(AppTest.class);

    MuProcessManager mngr = null;

    @Override
    protected void setUp() throws Exception {
        super.setUp();

        try {
            System.out.println();
            mngr = MuProcessManager.getManager();
            mngr.start();

        }
        catch (Exception e) {
            String info = "Failed to initiate: " + e.getMessage();
            log.warn(info, e);
        }
    }

    @Override
    protected void tearDown() throws Exception {
        super.tearDown();

        if (null != mngr) {
            mngr.stop();
            mngr = null;
        }
    }

    /**
     * Create the test case
     *
     * @param testName name of the test case
     */
    public AppTest( String testName )
    {
        super( testName );
    }

    /**
     * @return the suite of tests being tested
     */
    public static Test suite()
    {
        return new TestSuite( AppTest.class );
    }


    public void testVolatileProcess()
    {
        if (null == mngr) {
            fail("No MuProcessManager available: Is another process using our database?");
        }

        System.out.println("\n---- MuVolatileProcess test ----");
        MuVolatileProcess process = mngr.newVolatileProcess();

        try {
            MuActivityParameters parameters = new MuActivityParameters();
            parameters.put("arg1", "param1");
            process.execute(
                    p -> {
                        System.out.println("First forward activity: " + p);
                        return true;
                    },
                    p -> {
                        System.out.println("First backward activity: " + p);
                        return true;
                    },
                    parameters
            );

            parameters.put("arg2", 42);
            process.execute(
                    p -> {
                        System.out.println("Second forward activity: " + p);
                        return true;
                    },
                    p -> {
                        System.out.println("Second backward activity: " + p);
                        return true;
                    },
                    parameters
            );

            parameters.put("arg3", true);
            process.execute(
                    p -> {
                        System.out.println("Third forward activity: " + p);
                        if (/* failure? */ true) {
                            System.out.println("Simulated FAILURE");
                            return false; // i.e. FAILURE
                        } else {
                            return true;
                        }
                    },
                    p -> {
                        System.out.println("Third backward activity: " + p);
                        return false; // Even compensation failed
                    },
                    parameters
            );

            parameters.put("arg4", 22/7.0);
            process.execute(
                    p -> {
                        System.out.println("Fourth forward activity: " + p);
                        return true;
                    },
                    p -> {
                        System.out.println("Fourth backward activity: " + p);
                        return true;
                    },
                    parameters
            );
        }
        catch (MuProcessException mpe) {
            System.out.println("Failure during process execution: " + mpe.getMessage());
        }
    }

    public void testPersistedProcess()
    {
        if (null == mngr) {
            fail("No MuProcessManager available: Is another process using our database?");
        }

        System.out.println("\n---- MuProcess test ----");
        WorkQueue workQueue = WorkerQueueFactory.getWorkQueue(
            WorkerQueueFactory.Type.Multi,
            /* number of threads */ 8
        );

        workQueue.start();

        for (int i = 0; i < 1000000; i++) {
            workQueue.execute(() -> {
                String correlationId = UUID.randomUUID().toString();

                MuProcess process = null;
                try {
                    process = mngr.newProcess(correlationId);

                    MuActivityParameters parameters = new MuActivityParameters();
                    parameters.put("arg1", "param1");
                    process.execute(new FirstActivity(), parameters);

                    parameters.put("arg2", 42);
                    process.execute(new SecondActivity(), parameters);

                    parameters.put("arg3", true);
                    process.execute(new ThirdActivity(), parameters);

                    parameters.put("arg4", 22 / 7.0);
                    process.execute(new FourthActivity(), parameters);

                    process.finished();

                } catch (MuProcessBackwardActivityException mpbae) {
                    // Forward activity failed and so did some compensation activities
                    String info = "Process and compensation failure: " + mpbae.getMessage();
                    if (log.isTraceEnabled()) {
                        log.trace(info);
                    }
                } catch (MuProcessForwardActivityException mpfae) {
                    // Forward activity failed, but compensations were successful
                    String info = "No success, but managed to compensate: " + mpfae.getMessage();
                    if (log.isTraceEnabled()) {
                        log.trace(info);
                    }
                }
                catch (Throwable t) {
                    // Other reasons for failure not necessarily related to the activity
                    if (null != process) {
                        process.failed();
                    }

                    String info = "Process failure: " + t.getMessage();
                    log.warn(info);
                }
            });
        }

        do {
            try {
                Thread.sleep(20 * 1000); // 20 seconds
            }
            catch (InterruptedException ignore) {}
        } while (!workQueue.isEmpty());

        workQueue.stop();

        // Then wait another few minutes
        try {
            Thread.sleep(15 * 60 * 1000); // 15 minutes
        }
        catch (InterruptedException ignore) {}
    }
}
