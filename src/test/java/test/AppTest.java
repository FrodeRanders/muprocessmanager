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
import junit.framework.Test;
import junit.framework.TestCase;
import junit.framework.TestSuite;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.gautelis.vopn.queue.WorkQueue;
import org.gautelis.vopn.queue.WorkerQueueFactory;

import java.util.*;

public class AppTest extends TestCase {
    private static final Logger log = LogManager.getLogger(AppTest.class);
    private static final Object lock = new Object();

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
                    (p, r) -> {
                        System.out.println("First forward activity: " + p);
                        return true;
                    },
                    (p, s) -> {
                        System.out.println("First backward activity: " + p);
                        return true;
                    },
                    parameters
            );

            parameters.put("arg2", 42);
            process.execute(
                    (p, r) -> {
                        System.out.println("Second forward activity: " + p);
                        return true;
                    },
                    (p, s) -> {
                        System.out.println("Second backward activity: " + p);
                        return true;
                    },
                    parameters
            );

            parameters.put("arg3", true);
            process.execute(
                    (p, r) -> {
                        System.out.println("Third forward activity: " + p);
                        if (/* failure? */ true) {
                            System.out.println("Simulated FAILURE");
                            return false; // i.e. FAILURE
                        } else {
                            return true;
                        }
                    },
                    (p, s) -> {
                        System.out.println("Third backward activity: " + p);
                        return false; // Even compensation failed
                    },
                    parameters
            );

            parameters.put("arg4", 22/7.0);
            process.execute(
                    (p, r) -> {
                        System.out.println("Fourth forward activity: " + p);
                        return true;
                    },
                    (p, s) -> {
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

        final Collection<String> sampledCorrelationIds = new ArrayList<>();

        for (int i = 0; i < 100000; i++) {
            final String correlationId = UUID.randomUUID().toString();
            if (i % 1000 == 0) {
                // Sample each thousandth correlation ID
                synchronized (lock) {
                    sampledCorrelationIds.add(correlationId);
                }
            }

            workQueue.execute(() -> {
                MuProcess process = null;
                try {
                    process = mngr.newProcess(correlationId);

                    MuActivityParameters parameters = new MuActivityParameters();
                    parameters.put("weight", 100.0 * Math.random());
                    process.execute(
                            (p, r) -> {
                                double weight = (double) p.get("weight");
                                double realWeight = 0.83 * weight;
                                r.add(realWeight);
                                return !(Math.random() < /* forward failure probability */ 0.01);

                            }, parameters
                    );

                    parameters.put("hat-size", 42);
                    process.execute(
                            (p, r) -> {
                                double weight = (double) r.remove(0);
                                double stepTwoResult = weight * (int) p.get("hat-size");
                                return r.add(stepTwoResult);
                            },
                            new SecondActivityCompensation(),
                            parameters
                    );

                    parameters.put("shrink-head", true);
                    process.execute(new ThirdActivity(), parameters);

                    parameters.put("pi-kinda", 22 / 7.0);
                    process.execute(new FourthActivity(), parameters);

                    process.finished();

                } catch (MuProcessForwardBehaviourException mpfae) {
                    // Forward activity failed, but compensations were successful
                     log.trace("No success, but managed to compensate: {}", mpfae.getMessage());

                } catch (MuProcessBackwardBehaviourException mpbae) {
                    // Forward activity failed and so did some compensation activities
                    log.trace("Process and compensation failure: {}", mpbae.getMessage());
                }
                catch (Throwable t) {
                    // Other reasons for failure not necessarily related to the activity
                    if (null != process) {
                        process.failed();
                    }

                    log.warn("Process failure: {}", t.getMessage(), t);
                }
            });
        }

        do {
            System.out.println("\nProcess result samples: " + sampledCorrelationIds.size());
            try {
                // Iterate since we will modify collection
                synchronized (lock) {
                    Iterator<String> sit = sampledCorrelationIds.iterator();
                    while (sit.hasNext()) {
                        String correlationId = sit.next();

                        final StringBuffer info = new StringBuffer("correlationId=\"").append(correlationId).append("\"");
                        Optional<MuProcessState> _status = mngr.getProcessStatus(correlationId);
                        if (_status.isPresent()) {
                            MuProcessState status = _status.get();
                            info.append(" status=").append(status);

                            switch (status) {
                                case SUCCESSFUL:
                                    Optional<MuProcessResult> _result = mngr.getProcessResult(correlationId);
                                    _result.ifPresent(objects -> objects.forEach((v) -> info.append(" {").append(v).append("}")));
                                    sit.remove();
                                    break;

                                case NEW:
                                case PROGRESSING:
                                    // Check later
                                    break;

                                default:
                                    // No idea to recheck, but we will try to reset the process here -- faking a retry
                                    Optional<Boolean> isReset = mngr.resetProcess(correlationId);
                                    isReset.ifPresent(aBoolean -> info.append(" (was ").append(aBoolean ? "" : "NOT ").append("reset)"));
                                    sit.remove();
                                    break;
                            }
                            System.out.println(info);
                        }
                    }
                }

                Thread.sleep(20 * 1000); // 20 seconds
            }
            catch (InterruptedException | MuProcessException ignore) {}
        } while (!workQueue.isEmpty());

        workQueue.stop();

        System.out.println("\nAbandoned processes:");
        try {
            Collection<MuProcessDetails> details = mngr.salvageAbandonedProcesses();
            for (MuProcessDetails detail : details) {
                System.out.println(detail.asJson());
            }
        }
        catch (MuProcessException mupp) {
            String info = mupp.getMessage();
            log.warn(info);
        }
    }
}
