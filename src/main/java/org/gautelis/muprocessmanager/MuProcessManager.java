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
package org.gautelis.muprocessmanager;

import org.gautelis.vopn.db.Database;
import org.gautelis.vopn.db.utils.Derby;
import org.gautelis.vopn.db.utils.Manager;
import org.gautelis.vopn.db.utils.Options;
import org.gautelis.vopn.lang.ConfigurationTool;
import org.gautelis.vopn.queue.WorkQueue;
import org.gautelis.vopn.queue.WorkerQueueFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.util.*;

/**
 * Implements a micro-process manager.
 * <p>
 * Acquire a {@link MuProcess} from this manager and execute your {@link MuActivity}
 * in this process -- this manager will take care of potential failures by automatically
 * running compensations. Compensations are persisted to a relational database (in
 * the background).
 */
public class MuProcessManager {
    private static final Logger log = LoggerFactory.getLogger(MuProcessManager.class);

    //
    private final boolean acceptCompensationFailure;

    // Timers
    private Timer dumpStatisticsTimer = null;
    private Timer recoverTimer = null;

    private final WorkQueue recoverWorkQueue;

    //
    private final MuPersistentLog compensationLog;
    private final MuProcessManagementPolicy policy;
    private static final boolean DEBUG = false;

    //
    private boolean justStarted = true; // updated after first successful recover()


    private MuProcessManager(DataSource dataSource, Properties sqlStatements, MuProcessManagementPolicy policy) {
        compensationLog = new MuPersistentLog(dataSource, sqlStatements);
        this.policy = policy;

        acceptCompensationFailure = policy.acceptCompensationFailure();

        // Queue used to recover 'unattended' processes
        recoverWorkQueue = WorkerQueueFactory.getWorkQueue(
                WorkerQueueFactory.Type.Multi,
                policy.numberOfRecoveryThreads()
        );
    }

    /**
     * Starts the micro process manager, i.e. initiates the background tasks associated with
     * detecting stuck processes and (re-)compensating process tasks if the process has died.
     * Also initiates the statistics logging (in the background).
     */
    public void start() {

        // Schedule statistics dump, which will periodically log characteristics of the
        // compensation log.
        if (null == dumpStatisticsTimer) {
            TimerTask statisticsTask = new TimerTask() {

                @Override
                public void run() {
                    compensationLog.dumpStatistics();
                }
            };
            dumpStatisticsTimer = new Timer("statistics");
            int initialDelay = 1000; // 1 second
            dumpStatisticsTimer.scheduleAtFixedRate(
                    statisticsTask, initialDelay, 1000 * policy.secondsBetweenLoggingStatistics()
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

            recoverTimer = new Timer("recover");
            int initialDelay = 1000; // 1 second_
            recoverTimer.scheduleAtFixedRate(
                    cleanupTask, initialDelay, 1000 * policy.secondsBetweenRecoveryAttempts()
            );
        }
    }

    /**
     * Stops the micro process manager, i.e. stop all background tasks.
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
    }

    /* package private */ void recover() {
        log.trace("Running scheduled recovery...");

        // Prepare collecting statistics for each state and operation
        final int numStates = MuProcessState.values().length;
        final long[] recoverCount = new long[numStates];
        final long[] removeCount = new long[numStates];
        final long[] abandonCount = new long[numStates];
        for (int i = 0; i < numStates; i++) {
            recoverCount[i] = removeCount[i] = abandonCount[i] = 0L;
        }

        final long[] observations = { 0L }; // in order to increment below

        //
        try {
            final int processRetentionTime = 60 * 1000 * policy.minutesToTrackProcess();
            final int processRecompensationTime = 1000 * policy.secondsBetweenRecompensationAttempts();
            final int processAssumedStuckTime = 60 * 1000 * policy.minutesBeforeAssumingProcessStuck();

            compensationLog.recover((correlationId, processId, status, created, modified) -> {
                observations[0]++; // explicit code
                MuProcessState _status = MuProcessState.fromInt(status);

                // Check if process has been stuck?
                Date now = new Date();
                boolean isRipeForRemoval = modified.before(new Date(now.getTime() - processRetentionTime));

                //
                switch (_status) {
                    case NEW:
                        if (isRipeForRemoval) {
                            // Automatically remove after timeout
                            log.trace("Removing stuck process: correlationId=\"{}\", processId={}, status={}", correlationId, processId, _status);
                            compensationLog.remove(correlationId, processId);
                            removeCount[status]++;
                        }
                        break;

                    case PROGRESSING: {
                        boolean assumedStuck = modified.before(new Date(now.getTime() - processAssumedStuckTime));

                        if (assumedStuck) {
                            log.trace("Recovering stuck process: correlationId=\"{}\", processId={}, status={}", correlationId, processId, _status);

                            // Attempt compensation
                            recoverWorkQueue.execute(() -> {
                                // Since we don't have a micro process waiting, we will not propagate any
                                // exceptions
                                try {
                                    // Ignored returned exception -- we don't want to throw anything here
                                    MuProcess.compensate(compensationLog, correlationId, processId, acceptCompensationFailure);
                                    recoverCount[status]++;

                                } catch (MuProcessException unexpected) {
                                    log.info(
                                            "Failed to recover process: correlationId=\"{}\", processId={}, status={}: {}",
                                            correlationId, processId, _status, unexpected.getMessage()
                                    );

                                }
                            });
                        }
                    }
                        break;

                    case SUCCESSFUL:
                    case COMPENSATED:
                        if (isRipeForRemoval) {
                            // Automatically remove after timeout
                            log.trace("Removing retired process: correlationId=\"{}\", processId={}, status={}", correlationId, processId, _status);
                            compensationLog.remove(correlationId, processId);
                            removeCount[status]++;
                        }
                        break;

                    case COMPENSATION_FAILED:
                        // First time through, try to recompensate once
                        if (!justStarted && isRipeForRemoval) {
                            // Automatically abandon after timeout
                            log.trace("Abandoning process: correlationId=\"{}\", processId={}, status={}", correlationId, processId, _status);
                            compensationLog.abandon(correlationId, processId);
                            abandonCount[status]++;
                        }
                        else {
                            boolean isRipeForRecompensation = modified.before(new Date(now.getTime() - processRecompensationTime));

                            if (isRipeForRecompensation) {
                                log.trace("Recovering process: correlationId=\"{}\", processId={}, status={}", correlationId, processId, _status);

                                // Re-attempt compensation
                                recoverWorkQueue.execute(() -> {
                                    // Since we don't have a micro process waiting, we will not propagate any
                                    // exceptions
                                    try {
                                        // Ignored returned exception -- we don't want to throw anything here
                                        MuProcess.compensate(compensationLog, correlationId, processId, acceptCompensationFailure);
                                        recoverCount[status]++;

                                    } catch (MuProcessException unexpected) {
                                        log.info(
                                                "Failed to recover process: correlationId=\"{}\", processId={}, status={}: {}",
                                                correlationId, processId, _status, unexpected.getMessage()
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
        }
        catch (MuProcessException mpe) {
            String info = "Scheduled recovery failed: ";
            info += mpe.getMessage();
            log.info(info, mpe);
        }

        // Do some reporting
        boolean haveSomethingToDisplay = false;
        StringBuilder statistics = new StringBuilder();
        for (int i = 0; i < numStates; i++) {
            MuProcessState status = MuProcessState.fromInt(i);
            if (recoverCount[i] > 0) {
                statistics.append("{").append(recoverCount[i]).append(" attempted compensations from ").append(status).append("} ");
                haveSomethingToDisplay = true;
            }
            if (removeCount[i] > 0) {
                statistics.append("{").append(removeCount[i]).append(" removed from ").append(status).append("} ");
                haveSomethingToDisplay = true;
            }
            if (abandonCount[i] > 0) {
                statistics.append("{").append(abandonCount[i]).append(" abandoned from ").append(status).append("} ");
                haveSomethingToDisplay = true;
            }
        }
        statistics.append("{").append(observations[0]).append(" observed in total}");

        if (haveSomethingToDisplay) {
            log.info(statistics.toString());
        }
    }

    /**
     * Creates a new volatile process, a process that handles volatile activities that will not be
     * persisted. May be used to handle synchronous process execution, including Saga-style compensation.
     * Does not survive a power off.
     * @return a volatile {@link MuVolatileProcess}.
     */
    public MuVolatileProcess newVolatileProcess() {
        return new MuVolatileProcess(acceptCompensationFailure);
    }

    /**
     * Creates a new persisted process, a process that handles activities with compensations that are
     * persisted to database. Handles synchronous process executions, including Saga-style compensation,
     * but will also survive a power off after which the compensations are run asynchronously without
     * a running process and in the background.
     * @param correlationId a correlation ID identifying the business request.
     * @return a persisted {@link MuProcess}
     */
    public MuProcess newProcess(final String correlationId) {
        return new MuProcess(correlationId, compensationLog, acceptCompensationFailure);
    }

    /**
     * Retrieves process status ({@link MuProcessState}) for a process, identified by correlation ID.
     * {@link MuProcessState} is available for a time period after the corresponding {@link MuProcess}
     * has vanished.
     * @param correlationId identifies the business request initiating the process. Should remain unchanged if re-trying.
     * @return {@link MuProcessState} for process, identified by correlation ID, or {@link Optional#empty} if process not found.
     * @throws MuProcessException if failing to retrieve result
     */
    public Optional<MuProcessState> getProcessStatus(final String correlationId) throws MuProcessException {
        return compensationLog.getProcessStatus(correlationId);
    }

    /**
     * Retrieves process results from {@link MuProcessState#SUCCESSFUL} processes.
     * @param correlationId identifies the business request initiating the process. Should remain unchanged if re-trying.
     * @return {@link MuProcessResult} for process, identified by correlation ID, or {@link Optional#empty} if process not found.
     * @throws MuProcessException if failing to retrieve result
     * @throws MuProcessResultsUnavailable if process is not {@link MuProcessState#SUCCESSFUL SUCCESSFUL}
     */
    public Optional<MuProcessResult> getProcessResult(final String correlationId) throws MuProcessException {
        return compensationLog.getProcessResult(correlationId);
    }

    /**
     * Resets (possibly existing) process. If a process failed earlier and left some activities
     * with status {@link MuProcessState#COMPENSATION_FAILED COMPENSATION_FAILED}, they have to
     * be removed from background activities of the process manager that may still try to individually
     * compensate them. If this is not done and we issue a new process for the same business request,
     * successful activities may later be undone by the process manager, still trying to compensate
     * the activity from an earlier run process.
     * <p>
     * This method will remove remnants of earlier run processes for the same business request
     * (as identified by the correlation ID) in preparation for a new process with the same intent.
     * <p>
     * This method is not supposed to be issued while a running synchronous process exists.
     * @param correlationId correlation ID identifying the business request initiating the process
     * @return true if a matching process was found and reset, false if no matching process was found
     * @throws MuProcessException upon failure
     */
    public Optional<Boolean> resetProcess(final String correlationId) throws MuProcessException {
        return compensationLog.resetProcess(correlationId);
    }

    /**
     * Salvages abandoned processes, returning details of processes and their activities;
     * @return a collection of process details.
     * @throws MuProcessException upon failure.
     */
    public Collection<MuProcessDetails> salvageAbandonedProcesses() throws MuProcessException {
        return compensationLog.salvageAbandonedProcesses();
    }

    ///////////////////////////////////////////////////////////////////////////////////////////////////////////////

    /**
     * Returns a {@link MuProcessManager} that uses an external database for persisting process information.
     * @param dataSource a datasource for an external database.
     * @param sqlStatements a lookup table containing SQL statements, see <a href="file:doc-files/sql-statements.html">SQL statements reference</a>.
     * @param policy the process management policy for the operation.
     * @return {@link MuProcessManager}
     */
    public static MuProcessManager getManager(
            DataSource dataSource, Properties sqlStatements, MuProcessManagementPolicy policy
    ) {
        return new MuProcessManager(dataSource, sqlStatements, policy);
    }

    /**
     * Returns a {@link MuProcessManager} that uses an external database for persisting process information.
     * Will use default SQL statements and policy.
     * @param dataSource a datasource for an external database
     * @return {@link MuProcessManager}
     * @throws MuProcessException if failing to load default SQL statements or policy
     */
    public static MuProcessManager getManager(DataSource dataSource) throws MuProcessException {
        try (InputStream sqlIs = MuProcessManager.class.getResourceAsStream("sql-statements.xml")) {
            final Properties sqlStatements = new Properties();
            sqlStatements.loadFromXML(sqlIs);

            try (InputStream policyIs = MuProcessManagementPolicy.class.getResourceAsStream("default-management-policy.xml")) {
                final Properties policyProperties = new Properties();
                policyProperties.loadFromXML(policyIs);

                MuProcessManagementPolicy policy = ConfigurationTool.bindProperties(MuProcessManagementPolicy.class, policyProperties);
                return getManager(dataSource, sqlStatements, policy);
            }
        }
        catch (IOException ioe) {
            String info = "Failed to load SQL statements: ";
            info += ioe.getMessage();
            log.warn(info, ioe);

            throw new MuProcessException(info, ioe);
        }
    }

    private static MuProcessManager getManager(Database.Configuration config) throws MuProcessException {
        try {
            DataSource dataSource = Derby.getDataSource("mu_process_manager", config);

            MuProcessManager manager = getManager(dataSource);

            Options options = Options.getDefault();
            options.debug = DEBUG;
            Manager instance = new Derby(dataSource, options);

            manager.create(instance, new PrintWriter(System.out));
            return manager;

        } catch (Throwable t) {
            String info = "Failed to create process manager: ";
            info += t.getMessage();
            throw new MuProcessException(info, t);
        }
    }

    /**
     * Returns a {@link MuProcessManager} that internally uses an embedded Apache Derby database
     * for persisting process information. Appropriate during development and non-critical operation,
     * but consider using {@link MuProcessManager#getManager(DataSource, Properties, MuProcessManagementPolicy)} instead.
     * @return {@link MuProcessManager}
     * @throws MuProcessException if failing to prepare local database.
     */
    public static MuProcessManager getManager() throws MuProcessException {
        Properties properties = new Properties();
        try {
            try (InputStream is = MuProcessManager.class.getResourceAsStream("default-database-configuration.xml")) {
                properties.load(is);
            }
        } catch (IOException ioe) {
            String info = "Failed to create process manager: No embedded database configuration: ";
            info += ioe.getMessage();
            throw new MuProcessException(info, ioe);
        }
        return getManager(Database.getConfiguration(properties));
    }

    /**
     * Creates the database objects (tables, etc).
     * <p>
     *
     * @throws Exception
     */
    private void create(Manager manager, PrintWriter out) throws Exception {
        try (InputStream is = getClass().getResourceAsStream("default-database-create.sql")) {
            manager.execute("default-database-create.sql", new InputStreamReader(is), out);
        }
    }

    /**
     * Initiates the database with basic content (if needed).
     * <p>
     *
     * @throws Exception
     */
    private void initiate(Manager manager, PrintWriter out) throws Exception {
        try (InputStream is = getClass().getResourceAsStream("default-database-initiate.sql")) {
            manager.execute("default-database-initiate.sql", new InputStreamReader(is), out);
        }
    }
}
