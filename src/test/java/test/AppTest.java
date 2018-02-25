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

import org.gautelis.muprocessmanager.*;
import org.gautelis.muprocessmanager.*;
import org.gautelis.muprocessmanager.queue.WorkQueue;
import org.gautelis.muprocessmanager.queue.WorkerQueueFactory;
import junit.framework.Test;
import junit.framework.TestCase;
import junit.framework.TestSuite;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.*;

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

        final Collection<String> sampledCorrelationIds = new LinkedList<>();

        for (int i = 0; i < 100000; i++) {
            final int[] j = { i };

            workQueue.execute(() -> {
                String correlationId = UUID.randomUUID().toString();
                if (j[0] % 1000 == 0) {
                    // Sample each thousandth correlation ID
                    sampledCorrelationIds.add(correlationId);
                }

                MuProcess process = null;
                try {
                    process = mngr.newProcess(correlationId);

                    MuProcessResult result = new MuProcessResult("This is a result");

                    MuActivityParameters parameters = new MuActivityParameters();
                    parameters.put("arg1", "param1");
                    process.execute(new FirstActivity(), parameters);

                    parameters.put("arg2", 42);
                    process.execute(
                            (p) -> result.add(10 * (int) p.get("arg2")),
                            new SecondActivityCompensation(),
                            parameters
                    );

                    parameters.put("arg3", true);
                    process.execute(new ThirdActivity(), parameters);

                    parameters.put("arg4", 22 / 7.0);
                    process.execute(new FourthActivity(), parameters);

                    result.add("This is another part of the result");
                    process.finished(result);

                } catch (MuProcessBackwardBehaviourException mpbae) {
                    // Forward activity failed and so did some compensation activities
                    String info = "Process and compensation failure: " + mpbae.getMessage();
                    if (log.isTraceEnabled()) {
                        log.trace(info);
                    }
                } catch (MuProcessForwardBehaviourException mpfae) {
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
                    log.warn(info, t);
                }
            });
        }

        do {
            System.out.println("\nProcess result samples:");
            try {
                // Iterate since we will modify collection
                Iterator<String> sit = sampledCorrelationIds.iterator();
                while (sit.hasNext()) {
                    String correlationId = sit.next();

                    System.out.print("correlationId=" + correlationId);
                    Optional<MuProcessStatus> _status = mngr.getProcessStatus(correlationId);
                    if (_status.isPresent()) {
                        MuProcessStatus status = _status.get();
                        System.out.print(" status=" + status);

                        switch (status) {
                            case SUCCESSFUL:
                                Optional<MuProcessResult> _result = mngr.getProcessResult(correlationId);
                                _result.ifPresent(objects -> objects.forEach((v) -> System.out.print(" {" + v + "}")));
                                sit.remove();
                                break;

                            case NEW:
                            case PROGRESSING:
                                // Check later
                                break;

                            default:
                                // No idea to recheck
                                sit.remove();
                                break;
                        }
                    }
                    else {
                        System.out.print(" (running transaction, status not yet visible) ");
                    }
                    System.out.println();
                }

                Thread.sleep(20 * 1000); // 20 seconds
            }
            catch (InterruptedException | MuProcessException ignore) {}
        } while (!workQueue.isEmpty());

        workQueue.stop();
    }
}
