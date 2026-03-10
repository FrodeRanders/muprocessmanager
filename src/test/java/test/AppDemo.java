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
package test;

import junit.framework.Test;
import junit.framework.TestCase;
import junit.framework.TestSuite;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.gautelis.muprocessmanager.*;
import org.gautelis.muprocessmanager.payload.MuNativeActivityParameters;
import org.gautelis.muprocessmanager.payload.MuNativeProcessResult;
import org.gautelis.vopn.queue.WorkQueue;
import org.gautelis.vopn.queue.WorkerQueueFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.Optional;
import java.util.UUID;

import static org.gautelis.muprocessmanager.MuProcessManagerFactory.getManager;

/**
 * Legacy demo and stress harness. This class is intentionally not named with a *Test suffix,
 * so it is excluded from the default Maven test run. Execute explicitly with:
 * {@code mvn -Dtest=test.AppDemo test}
 */
public class AppDemo extends TestCase {
    private static final Logger log = LogManager.getLogger(AppDemo.class);
    private static final Object lock = new Object();
    private static final int DEFAULT_PROCESS_COUNT = Integer.getInteger("muprocessmanager.appTest.processes", 5000);
    private static final int SAMPLE_DIVISOR = Integer.getInteger("muprocessmanager.appTest.sampleDivisor", 100);
    private static final long POLL_INTERVAL_MILLIS = Long.getLong("muprocessmanager.appTest.pollMillis", 1000L);

    private MuProcessManager mngr = null;

    @Override
    protected void setUp() throws Exception {
        super.setUp();

        try {
            System.out.println();
            mngr = getManager();
            mngr.start();

        } catch (Exception e) {
            String info = "Failed to initiate: " + e.getMessage();
            System.out.println(info);
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

    public AppDemo(String testName) {
        super(testName);
    }

    public static Test suite() {
        return new TestSuite(AppDemo.class);
    }

    public void testVolatileProcess() {
        if (null == mngr) {
            fail("No MuProcessManager available: Is another process using our database?");
        }

        System.out.println("\n---- MuVolatileProcess test ----");
        final String correlationId = UUID.randomUUID().toString();
        MuVolatileProcess process = mngr.newVolatileProcess(correlationId);

        try {
            MuNativeActivityParameters parameters = new MuNativeActivityParameters();
            parameters.put("arg1", "param1");
            process.execute(
                    c -> {
                        System.out.println("First forward activity: " + c.getActivityParameters());
                        return true;
                    },
                    c -> {
                        System.out.println("First backward activity: " + c.getActivityParameters());
                        return true;
                    },
                    parameters
            );

            parameters.put("arg2", 42);
            process.execute(
                    c -> {
                        System.out.println("Second forward activity: " + c.getActivityParameters());
                        return true;
                    },
                    c -> {
                        System.out.println("Second backward activity: " + c.getActivityParameters());
                        return true;
                    },
                    parameters
            );

            parameters.put("arg3", true);
            process.execute(
                    c -> {
                        System.out.println("Third forward activity: " + c.getActivityParameters());
                        if (true) {
                            System.out.println("Simulated FAILURE");
                            return false;
                        } else {
                            return true;
                        }
                    },
                    c -> {
                        System.out.println("Third backward activity: " + c.getActivityParameters());
                        return false;
                    },
                    parameters
            );

            parameters.put("arg4", 22 / 7.0);
            process.execute(
                    c -> {
                        System.out.println("Fourth forward activity: " + c.getActivityParameters());
                        return true;
                    },
                    c -> {
                        System.out.println("Fourth backward activity: " + c.getActivityParameters());
                        return true;
                    },
                    parameters
            );
        } catch (MuProcessException mpe) {
            System.out.println("Failure during process execution: " + mpe.getMessage());
        }
    }

    public void testPersistedProcess() {
        if (null == mngr) {
            fail("No MuProcessManager available: Is another process using our database?");
        }

        System.out.println("\n---- MuProcess test ----");
        WorkQueue workQueue = WorkerQueueFactory.getWorkQueue(
                WorkerQueueFactory.Type.Multi,
                Runtime.getRuntime().availableProcessors()
        );

        workQueue.start();

        if (false) {
            try {
                MuProcessManager competingMngr = getManager();
                competingMngr.start();
            } catch (MuProcessException mpe) {
                String info = "Failed to initiate competing manager: ";
                info += mpe.getMessage();
                fail(info);
            }
        }
        final Collection<String> sampledCorrelationIds = new ArrayList<>();
        final int sampleEvery = Math.max(1, DEFAULT_PROCESS_COUNT / Math.max(1, SAMPLE_DIVISOR));

        for (int i = 0; i < DEFAULT_PROCESS_COUNT; i++) {
            final String correlationId = UUID.randomUUID().toString();
            if (i % sampleEvery == 0) {
                synchronized (lock) {
                    sampledCorrelationIds.add(correlationId);
                }
            }

            workQueue.execute(() -> {
                MuProcess process = null;
                try {
                    process = mngr.newProcess(correlationId);

                    MuNativeActivityParameters parameters = new MuNativeActivityParameters();
                    parameters.put("weight", 100.0 * Math.random());
                    process.execute(
                            c -> {
                                if (c.usesNativeDataFlow()) {
                                    MuNativeActivityParameters np = (MuNativeActivityParameters) c.getActivityParameters();
                                    MuNativeProcessResult nr = (MuNativeProcessResult) c.getResult();
                                    double weight = (double) np.get("weight");
                                    double realWeight = 0.83 * weight;
                                    nr.add(realWeight);
                                }
                                return !(Math.random() < 0.01);
                            }, parameters
                    );

                    parameters.put("hat-size", 42);
                    process.execute(
                            c -> {
                                if (c.usesNativeDataFlow()) {
                                    MuNativeActivityParameters np = (MuNativeActivityParameters) c.getActivityParameters();
                                    MuNativeProcessResult nr = (MuNativeProcessResult) c.getResult();
                                    double weight = (double) nr.remove(0);
                                    double stepTwoResult = weight * (int) np.get("hat-size");
                                    nr.add(stepTwoResult);
                                }
                                return true;
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
                    log.trace("No success, but managed to compensate: {}", mpfae.getMessage());

                } catch (MuProcessBackwardBehaviourException mpbae) {
                    log.trace("Process and compensation failure: {}", mpbae.getMessage());
                } catch (Throwable t) {
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
                synchronized (lock) {
                    Iterator<String> sit = sampledCorrelationIds.iterator();
                    while (sit.hasNext()) {
                        String correlationId = sit.next();

                        final StringBuffer info = new StringBuffer("correlationId=\"").append(correlationId).append("\"");
                        Optional<MuProcessState> _state = mngr.getProcessState(correlationId);
                        if (_state.isPresent()) {
                            MuProcessState state = _state.get();
                            info.append(" state=").append(state);

                            switch (state) {
                                case SUCCESSFUL:
                                    Optional<MuProcessResult> _result = mngr.getProcessResult(correlationId);
                                    _result.ifPresent(objects -> {
                                        if (objects.isNative()) {
                                            MuNativeProcessResult nativeResult = (MuNativeProcessResult) objects;
                                            nativeResult.forEach((v) -> info.append(" result=").append(v));
                                        }
                                    });
                                    sit.remove();
                                    break;

                                case NEW:
                                case PROGRESSING:
                                    break;

                                default:
                                    Optional<Boolean> isReset = mngr.resetProcess(correlationId);
                                    isReset.ifPresent(aBoolean -> info.append(" (successfully ").append(aBoolean ? "" : "NOT ").append("reset)"));
                                    sit.remove();
                                    break;
                            }
                            System.out.println(info);
                        }
                    }
                }

                Thread.sleep(POLL_INTERVAL_MILLIS);
            } catch (InterruptedException | MuProcessException ignore) {
            }
        } while (!workQueue.isEmpty());

        workQueue.stop();

        System.out.println("\nAbandoned processes:");
        try {
            Collection<MuProcessDetails> details = mngr.getAbandonedProcessDetails();
            for (MuProcessDetails detail : details) {
                System.out.println(detail.toJson());
            }
        } catch (MuProcessException mupp) {
            log.warn(mupp.getMessage());
        }
    }
}
