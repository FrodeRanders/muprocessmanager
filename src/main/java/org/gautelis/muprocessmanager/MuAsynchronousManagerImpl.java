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

import org.gautelis.vopn.queue.WorkQueue;
import org.gautelis.vopn.queue.WorkerQueueFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.util.*;

/**
 * Implements the asynchronous parts of the micro-process manager, taking care of
 * background activities.
 * <p>
 * Acquire a {@link MuProcess} from this manager and execute your {@link MuActivity}
 * in this process -- this manager will take care of potential failures by automatically
 * running compensations. Compensations are persisted to a relational database (in
 * the background).
 */
public class MuAsynchronousManagerImpl implements MuAsynchronousManager {
    private static final Logger log = LoggerFactory.getLogger(MuAsynchronousManagerImpl.class);

    // Timers
    private Timer dumpStatisticsTimer = null;
    private Timer recoverTimer = null;

    private final WorkQueue recoverWorkQueue;

    //
    private final MuPersistentLog compensationLog;
    private final MuProcessManagementPolicy policy;

    //
    private boolean justStarted = true; // updated after first successful recover()


    /* package private */
    MuAsynchronousManagerImpl(DataSource dataSource, Properties sqlStatements, MuProcessManagementPolicy policy) {
        Objects.requireNonNull(dataSource, "dataSource");
        Objects.requireNonNull(sqlStatements, "sqlStatements");
        Objects.requireNonNull(policy, "policy");

        boolean assumeNativeProcessDataFlow = policy.assumeNativeProcessDataFlow();

        compensationLog = new MuPersistentLog(dataSource, sqlStatements, assumeNativeProcessDataFlow);
        this.policy = policy;

        // Queue used to recover 'unattended' processes
        recoverWorkQueue = WorkerQueueFactory.getWorkQueue(
                WorkerQueueFactory.Type.Multi, // WorkerQueueFactory.Type.WorkStealing,
                policy.numberOfRecoveryThreads()
        );
    }

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
    public void start() {
        // Schedule statistics dump, which will periodically log characteristics of the
        // compensation log.
        if (null == dumpStatisticsTimer) {
            TimerTask statisticsTask = new TimerTask() {

                @Override
                public void run() {
                    compensationLog.dumpStatistics(recoverWorkQueue);
                }
            };
            dumpStatisticsTimer = new Timer("org.gautelis.muprocessmanager.statistics");
            int initialDelay = 1000; // 1 second
            dumpStatisticsTimer.scheduleAtFixedRate(
                    statisticsTask, initialDelay, 1000L * policy.secondsBetweenLoggingStatistics()
            );
        }

        // Schedule abandoned processes cleanup, which will periodically check for abandoned
        // processes
        recoverWorkQueue.start();

        if (null == recoverTimer) {
            TimerTask cleanupTask = new TimerTask() {
                @Override
                public void run() {
                    recover();
                }
            };

            recoverTimer = new Timer("org.gautelis.muprocessmanager.recover");
            int initialDelay = 1000 + (int)Math.round(Math.random() * 5000); // 1+ seconds
            recoverTimer.scheduleAtFixedRate(
                    cleanupTask, initialDelay, 1000L * policy.secondsBetweenRecoveryAttempts()
            );
        }

        System.out.println("Process manager asynchronous background task started.");
    }

    /**
     * Stops the micro process manager asynchronous background tasks.
     * <p>
     * As long as these tasks are running, the program will not exit.
     */
    public void stop() {
        if (null != dumpStatisticsTimer) {
            dumpStatisticsTimer.cancel();
            dumpStatisticsTimer = null;
        }

        if (null != recoverTimer) {
            recoverTimer.cancel();
            recoverTimer = null;
        }

        recoverWorkQueue.stop();

        System.out.println("Process manager asynchronous background task stopped.");
    }

    /* package private */
    void recover() {
        log.trace("Running scheduled recovery...");

        long size;
        int waitLeft = 1000 * ((policy.secondsBetweenRecoveryAttempts() * 2) / 3); // two third of full cycle
        do {
            size = recoverWorkQueue.size();
            if (size > 0L) {
                try {
                    log.debug("Background threads not yet ready... {} in queue [delay]", size);
                    Thread.sleep(1000); // 1 second
                    waitLeft -= 1000;
                } catch (InterruptedException ignore) {}
            }
        } while (size > 0L && waitLeft > 0);

        if (size > 0L) {
            log.warn("Postponing recover in order to catch up... {} in queue", size);
            return;
        }

        // Prepare collecting statistics for each state and operation
        final int numStates = MuProcessState.values().length;
        final long[] recoverCount = new long[numStates];
        final long[] removeCount = new long[numStates];
        final long[] abandonCount = new long[numStates];
        for (int i = 0; i < numStates; i++) {
            recoverCount[i] = removeCount[i] = abandonCount[i] = 0L;
        }

        final long[] observations = {0L}; // mutable in closure

        //
        try {
            final long processRetentionTime = 60L * 1000 * policy.minutesToTrackProcess();
            final long processRecompensationTime = 1000L * policy.secondsBetweenRecompensationAttempts();
            final long processAssumedStuckTime = 60L * 1000 * policy.minutesBeforeAssumingProcessStuck();

            compensationLog.recover(
                    (correlationId, processId, state, acceptCompensationFailure, created, modified, now) -> {

                observations[0]++; // explicit code
                MuProcessState _state = MuProcessState.fromInt(state);

                //
                switch (_state) {
                    case NEW:
                        if (/* Assumed stuck */ modified.before(new Date(now.getTime() - processAssumedStuckTime))) {
                            recoverWorkQueue.execute(() -> {
                                try {
                                    log.debug("Removing stuck process: correlationId=\"{}\", processId={}, state={}", correlationId, processId, _state);
                                    compensationLog.remove(correlationId, processId, modified);
                                    removeCount[state]++;

                                } catch (MuProcessException mpe) {
                                    String info = "Failed to remove stuck process: ";
                                    info += mpe.getMessage();
                                    log.info(info, mpe);
                                }
                            });
                        }
                        break;

                    case PROGRESSING: {
                        if (/* Assumed stuck */ modified.before(new Date(now.getTime() - processAssumedStuckTime))) {

                            // Attempt compensation
                            recoverWorkQueue.execute(() -> {
                                log.debug("Recovering stuck process: correlationId=\"{}\", processId={}, state={}", correlationId, processId, _state);

                                // Since we don't have a micro process waiting, we will not propagate any
                                // exceptions
                                try {
                                    // Ignored returned exception -- we don't want to throw anything here
                                    // since we are running compensation asynchronously. It is safe to compensate
                                    // here (since we cannot be re-compensating -- process was progressing -- and
                                    // re-compensation may not be allowed if we failed at it earlier)
                                    //
                                    //noinspection ThrowableNotThrown
                                    MuProcess.compensate(compensationLog, correlationId, processId);
                                    recoverCount[state]++;

                                } catch (MuProcessException unexpected) {
                                    log.info(
                                            "Failed to recover process: correlationId=\"{}\", processId={}, state={}: {}",
                                            correlationId, processId, _state, unexpected.getMessage()
                                    );

                                }
                            });
                        }
                    }
                    break;

                    case SUCCESSFUL:
                    case COMPENSATED:
                        if (/* Is ripe for removal */ modified.before(new Date(now.getTime() - processRetentionTime))) {
                            recoverWorkQueue.execute(() -> {
                                try {
                                    log.trace("Removing retired process: correlationId=\"{}\", processId={}, state={}", correlationId, processId, _state);
                                    compensationLog.remove(correlationId, processId, modified);
                                    removeCount[state]++;

                                } catch (MuProcessException mpe) {
                                    String info = "Failed to remove retired process: ";
                                    info += mpe.getMessage();
                                    log.info(info, mpe);
                                }
                            });
                        }
                        break;

                    case COMPENSATION_FAILED:
                        // If this process doesn't allow re-compensations, we will fail and abandon process right away.
                        // Otherwise, if this is the first time through, we will try to re-compensate at least once
                        if (!acceptCompensationFailure
                                || (!justStarted && /* Is ripe for removal */ modified.before(new Date(now.getTime() - processRetentionTime)))) {
                            recoverWorkQueue.execute(() -> {
                                try {
                                    Optional<Integer> stepCount = compensationLog.countProcessSteps(processId);
                                    if (stepCount.isPresent() && stepCount.get() > 0) {
                                        log.debug("Abandoning process{}: correlationId=\"{}\", processId={}, state={}",
                                                acceptCompensationFailure ? "" : " (since re-compensation prohibited)", correlationId, processId, _state);
                                        compensationLog.abandon(correlationId, processId);
                                        abandonCount[state]++;

                                    } else {
                                        // Since there are no process steps, and thus no pending compensations,
                                        // we will mark this process as compensated
                                        log.debug("Marking process as compensated: correlationId=\"{}\", processId={}, state={}", correlationId, processId, _state);
                                        compensationLog.cleanupAfterSuccessfulCompensation(processId);
                                        recoverCount[state]++;
                                    }
                                } catch (MuProcessException mpe) {
                                    String info = "Failed to abandon process: ";
                                    info += mpe.getMessage();
                                    log.info(info, mpe);
                                }
                            });
                        } else {
                            if (/* Is ripe for recompensation */ modified.before(new Date(now.getTime() - processRecompensationTime))) {

                                // Re-attempt compensation
                                recoverWorkQueue.execute(() -> {
                                    log.trace("Recovering process: correlationId=\"{}\", processId={}, state={}", correlationId, processId, _state);

                                    // Since we don't have a micro process waiting, we will not propagate any
                                    // exceptions
                                    try {
                                        // Ignored returned exception -- we don't want to throw anything here
                                        //noinspection ThrowableNotThrown
                                        MuProcess.compensate(compensationLog, correlationId, processId);
                                        recoverCount[state]++;

                                    } catch (MuProcessException unexpected) {
                                        log.info(
                                                "Failed to recover process: correlationId=\"{}\", processId={}, state={}: {}",
                                                correlationId, processId, _state, unexpected.getMessage()
                                        );
                                    }
                                });
                            }
                        }
                        break;

                    default:
                        // Do nothing!!!
                        break;
                }
            });

            // Having run recover() once, we have at least tried to recompensate
            // processes in COMPENSATION_FAILED once.
            justStarted = false;

        } catch (MuProcessException mpe) {
            String info = "Scheduled recovery failed: ";
            info += mpe.getMessage();
            log.info(info, mpe);
        }

        // Do some reporting
        boolean haveSomethingToDisplay = false;
        StringBuilder statistics = new StringBuilder();
        for (int i = 0; i < numStates; i++) {
            MuProcessState state = MuProcessState.fromInt(i);
            if (recoverCount[i] > 0) {
                statistics.append("{").append(recoverCount[i]).append(" attempted compensations from ").append(state).append("} ");
                haveSomethingToDisplay = true;
            }
            if (removeCount[i] > 0) {
                statistics.append("{").append(removeCount[i]).append(" removed from ").append(state).append("} ");
                haveSomethingToDisplay = true;
            }
            if (abandonCount[i] > 0) {
                statistics.append("{").append(abandonCount[i]).append(" abandoned from ").append(state).append("} ");
                haveSomethingToDisplay = true;
            }
        }
        statistics.append("{").append(observations[0]).append(" observed in total} ");
        statistics.append("{").append(recoverWorkQueue.size()).append(" in queue} ");

        if (haveSomethingToDisplay) {
            log.info(statistics.toString());
        }
    }
}
