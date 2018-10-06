/*
 * Copyright (C) 2017-2018 Frode Randers
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
import org.gautelis.vopn.db.DatabaseException;
import org.gautelis.vopn.db.utils.Derby;
import org.gautelis.vopn.db.utils.Manager;
import org.gautelis.vopn.db.utils.Options;
import org.gautelis.vopn.lang.ConfigurationTool;
import org.gautelis.vopn.queue.WorkQueue;
import org.gautelis.vopn.queue.WorkerQueueFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.io.*;
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
    private final boolean assumeNativeProcessDataFlow;
    private final boolean onlyCompensateIfTransactionWasSuccessful;

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
        acceptCompensationFailure = policy.acceptCompensationFailure();
        assumeNativeProcessDataFlow = policy.assumeNativeProcessDataFlow();
        onlyCompensateIfTransactionWasSuccessful = policy.onlyCompensateIfTransactionWasSuccessful();

        compensationLog = new MuPersistentLog(dataSource, sqlStatements, assumeNativeProcessDataFlow);
        this.policy = policy;

        // Queue used to recover 'unattended' processes
        recoverWorkQueue = WorkerQueueFactory.getWorkQueue(
                WorkerQueueFactory.Type.Multi,
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

            recoverTimer = new Timer("org.gautelis.muprocessmanager.recover");
            int initialDelay = 1000 + (int)Math.round(Math.random() * 5000); // 1+ seconds
            recoverTimer.scheduleAtFixedRate(
                    cleanupTask, initialDelay, 1000 * policy.secondsBetweenRecoveryAttempts()
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

    /* package private */ void recover() {
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
            final int processRetentionTime = 60 * 1000 * policy.minutesToTrackProcess();
            final int processRecompensationTime = 1000 * policy.secondsBetweenRecompensationAttempts();
            final int processAssumedStuckTime = 60 * 1000 * policy.minutesBeforeAssumingProcessStuck();

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

    /**
     * Creates a new volatile process, a process that handles volatile activities that will not be
     * persisted. May be used to handle synchronous process execution, including Saga-style compensation.
     * Does not survive a power off.
     * @param correlationId a correlation ID identifying the business request.
     * @return a volatile {@link MuVolatileProcess}.
     */
    public MuVolatileProcess newVolatileProcess(final String correlationId) {
        return new MuVolatileProcess(correlationId, acceptCompensationFailure, assumeNativeProcessDataFlow);
    }

    /**
     * Creates a new persisted process, a process that handles activities with compensations that are
     * persisted to database. Handles synchronous process executions, including Saga-style compensation,
     * but will also survive a power off after which the compensations are run asynchronously without
     * a running process and in the background.
     * <p>
     * This version of the 'newProcess' method falls back on the globally defined process manager
     * policy for determining re-compensation acceptance. If this cannot be defined globally for
     * all processes, use the more specific {@link MuProcessManager#newProcess(String, boolean)} instead.
     *
     * @param correlationId a correlation ID identifying the business request.
     * @return a persisted {@link MuProcess}
     */
    public MuProcess newProcess(final String correlationId) {
        return new MuProcess(
                correlationId, compensationLog,
                acceptCompensationFailure,
                assumeNativeProcessDataFlow,
                onlyCompensateIfTransactionWasSuccessful
        );
    }

    /**
     * Creates a new persisted process, a process that handles activities with compensations that are
     * persisted to database. Handles synchronous process executions, including Saga-style compensation,
     * but will also survive a power off after which the compensations are run asynchronously without
     * a running process and in the background.
     * <p>
     * If there are explicit demands on Serializability, re-compensation should not be allowed. This can be
     * set for all processes by means of the {@link MuProcessManagementPolicy}, or by this method on a per
     * process basis.
     *
     * @param correlationId a correlation ID identifying the business request.
     * @param acceptCompensationFailure indicate (on a per process basis) whether re-compensation is allowed.
     * @return a persisted {@link MuProcess}
     */
    public MuProcess newProcess(final String correlationId, boolean acceptCompensationFailure) {
        return new MuProcess(
                correlationId, compensationLog,
                acceptCompensationFailure,
                assumeNativeProcessDataFlow,
                onlyCompensateIfTransactionWasSuccessful
        );
    }

    /**
     * Retrieves process state ({@link MuProcessState}) for a process, identified by correlation ID.
     * {@link MuProcessState} is available for a time period after the corresponding {@link MuProcess}
     * has vanished.
     *
     * @param correlationId identifies the business request initiating the process. Should remain unchanged if re-trying.
     * @return {@link MuProcessState} for process, identified by correlation ID, or {@link Optional#empty} if process not found.
     * @throws MuProcessException if failing to retrieve result
     */
    public Optional<MuProcessState> getProcessState(final String correlationId) throws MuProcessException {
        return compensationLog.getProcessState(correlationId);
    }

    /**
     * Retrieves process results from {@link MuProcessState#SUCCESSFUL} processes.
     *
     * @param correlationId identifies the business request initiating the process. Should remain unchanged if re-trying.
     * @return {@link MuProcessResult} for process, identified by correlation ID, or {@link Optional#empty} if process not found.
     * @throws MuProcessException          if failing to retrieve result
     * @throws MuProcessResultsUnavailable if process is not {@link MuProcessState#SUCCESSFUL SUCCESSFUL}
     */
    public Optional<MuProcessResult> getProcessResult(final String correlationId) throws MuProcessException {
        return compensationLog.getProcessResult(correlationId);
    }

    /**
     * Resets (possibly existing) process. If a process failed earlier and left some activities
     * with state {@link MuProcessState#COMPENSATION_FAILED COMPENSATION_FAILED}, they have to
     * be removed from background activities of the process manager that may still try to individually
     * compensate them. If this is not done and we issue a new process for the same business request,
     * successful activities may later be undone by the process manager, still trying to compensate
     * the activity from an earlier run process.
     * <p>
     * This method will remove remnants of earlier run processes for the same business request
     * (as identified by the correlation ID) in preparation for a new process with the same intent.
     * <p>
     * This method is not supposed to be issued while a running synchronous process exists.
     *
     * @param correlationId correlation ID identifying the business request initiating the process
     * @return true if a matching process was found and reset, false if no matching process was found
     * @throws MuProcessException upon failure
     */
    public Optional<Boolean> resetProcess(final String correlationId) throws MuProcessException {
        return compensationLog.resetProcess(correlationId);
    }

    /**
     * Retrieves abandoned processes, returning details of processes and their activities.
     *
     * @return a collection of process details.
     * @throws MuProcessException upon failure.
     */
    public Collection<MuProcessDetails> getAbandonedProcessDetails() throws MuProcessException {
        return compensationLog.getAbandonedProcessDetails();
    }

    /**
     * Retrieves details for all (known) processes, returning process details as well as details
     * on activities.
     *
     * @return a collection of process details.
     * @throws MuProcessException upon failure.
     */
    public Collection<MuProcessDetails> getProcessDetails() throws MuProcessException {
        return compensationLog.getProcessDetails();
    }

    /**
     * Retrieves processes, returning details of processes and their activities;
     *
     * @return details for process identified by correlationId.
     * @throws MuProcessException upon failure.
     */
    public Optional<MuProcessDetails> getProcessDetails(String correlationId) throws MuProcessException {
        return compensationLog.getProcessDetails(correlationId);
    }

    ///////////////////////////////////////////////////////////////////////////////////////////////////////////////

    /**
     * Returns a {@link MuProcessManager} that uses an external database for persisting process information.
     *
     * @param dataSource    a datasource for an external database.
     * @param sqlStatements a lookup table containing SQL statements, see <a href="file:doc-files/sql-statements.html">SQL statements reference</a>.
     * @param policy        the process management policy for the operation.
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
     *
     * @param dataSource a datasource for an external database
     * @return {@link MuProcessManager}
     * @throws MuProcessException if failing to load default SQL statements or policy
     */
    public static MuProcessManager getManager(DataSource dataSource) throws MuProcessException {
        return getManager(dataSource, getDefaultSqlStatements(), getDefaultManagementPolicy());
    }

    /**
     * Returns a {@link MuProcessManager} that internally uses an embedded Apache Derby database
     * for persisting process information. Appropriate during development and non-critical operation,
     * but consider using {@link MuProcessManager#getManager(DataSource, Properties, MuProcessManagementPolicy)} instead.
     *
     * @return {@link MuProcessManager}
     * @throws MuProcessException if failing to prepare local database.
     */
    public static MuProcessManager getManager() throws MuProcessException {
        try {
            Database.Configuration databaseConfiguration = getDefaultDatabaseConfiguration();
            DataSource dataSource = Derby.getDataSource("mu_process_manager", databaseConfiguration);
            prepareInternalDatabase(dataSource);

            return getManager(dataSource);

        } catch (DatabaseException de) {
            String info = "Failed to create data source: ";
            info += de.getMessage();
            throw new MuProcessException(info, de);

        } catch (MuProcessException mpe) {
            String info = "Failed to create process manager: No embedded database configuration: ";
            info += mpe.getMessage();
            throw new MuProcessException(info, mpe);

        }
    }

    public static void prepareInternalDatabase(DataSource dataSource) throws MuProcessException {
        try {
            Options options = Options.getDefault();
            options.debug = DEBUG;
            Manager instance = new Derby(dataSource, options);

            create(instance, new PrintWriter(System.out));

        } catch (Throwable t) {
            String info = "Failed to prepare internal database: ";
            info += t.getMessage();
            throw new MuProcessException(info, t);
        }
    }

    /**
     * Creates the database objects (tables, etc).
     * <p>
     *
     * @throws Exception
     */
    private static void create(Manager manager, PrintWriter out) throws Exception {
        try (InputStream is = MuProcessManager.class.getResourceAsStream("default-database-create.sql")) {
            manager.execute("default-database-create.sql", new InputStreamReader(is), out);
        }
    }

    public static Database.Configuration getDatabaseConfiguration(File file) throws FileNotFoundException, MuProcessException {
        if (null == file) {
            throw new IllegalArgumentException("file");
        }

        if (!file.exists() || !file.canRead()) {
            throw new FileNotFoundException(file.getAbsolutePath());
        }

        try (InputStream is = new FileInputStream(file)) {
            Properties properties = new Properties();
            properties.loadFromXML(is);
            return Database.getConfiguration(properties);
        }
        catch (IOException ioe) {
            String info = "Failed to load database configuration from \"" + file.getAbsolutePath() + "\": ";
            info += ioe.getMessage();
            throw new MuProcessException(info, ioe);
        }
    }

    public static Database.Configuration getDatabaseConfiguration(Class clazz, String resource) throws MuProcessException {
        if (null == clazz) {
            throw new IllegalArgumentException("class");
        }

        try (InputStream is = clazz.getResourceAsStream(resource)) {
            if (null == is) {
                String info = "Unknown resource: class=\"" + clazz.getName() + "\", resource=\"" + resource + "\"";
                throw new IllegalArgumentException(info);
            }
            Properties properties = new Properties();
            properties.loadFromXML(is);
            return Database.getConfiguration(properties);

        } catch (IOException ioe) {
            String info = "Failed to load database configuration: ";
            info += ioe.getMessage();
            throw new MuProcessException(info, ioe);
        }
    }

    public static Database.Configuration getDefaultDatabaseConfiguration() throws MuProcessException {
        return getDatabaseConfiguration(MuProcessManager.class, "default-database-configuration.xml");
    }

    public static DataSource getDefaultDataSource(String applicationName) throws MuProcessException {
        Database.Configuration defaultDatabaseConfiguration = getDefaultDatabaseConfiguration();
        try  {
            return Derby.getDataSource(applicationName, defaultDatabaseConfiguration);

        } catch (DatabaseException de) {
            String info = "Failed to establish internal datasource: ";
            info += de.getMessage();
            throw new MuProcessException(info, de);
        }
    }

    public static Properties getSqlStatements(File file) throws FileNotFoundException, MuProcessException {
        if (null == file) {
            throw new IllegalArgumentException("file");
        }

        if (!file.exists() || !file.canRead()) {
            throw new FileNotFoundException(file.getAbsolutePath());
        }

        try (InputStream is = new FileInputStream(file)) {
            final Properties sqlStatements = new Properties();
            sqlStatements.loadFromXML(is);
            return sqlStatements;
        }
        catch (IOException ioe) {
            String info = "Failed to load SQL statements from \"" + file.getAbsolutePath() + "\": ";
            info += ioe.getMessage();
            throw new MuProcessException(info, ioe);
        }
    }

    public static Properties getSqlStatements(Class clazz, String resource) throws MuProcessException {
        if (null == clazz) {
            throw new IllegalArgumentException("class");
        }

        try (InputStream is = clazz.getResourceAsStream(resource)) {
            if (null == is) {
                String info = "Unknown resource: class=\"" + clazz.getName() + "\", resource=\"" + resource + "\"";
                throw new IllegalArgumentException(info);
            }
            final Properties sqlStatements = new Properties();
            sqlStatements.loadFromXML(is);
            return sqlStatements;
        }
        catch (IOException ioe) {
            String info = "Failed to load SQL statements: ";
            info += ioe.getMessage();
            throw new MuProcessException(info, ioe);
        }
    }

    public static Properties getDefaultSqlStatements() throws MuProcessException {
        return getSqlStatements(MuProcessManager.class, "sql-statements.xml");
    }

    public static MuProcessManagementPolicy getManagementPolicy(File file) throws FileNotFoundException, MuProcessException {
        if (null == file) {
            throw new IllegalArgumentException("file");
        }

        if (!file.exists() || !file.canRead()) {
            throw new FileNotFoundException(file.getAbsolutePath());
        }

        try (InputStream is = new FileInputStream(file)) {
            final Properties policyProperties = new Properties();
            policyProperties.loadFromXML(is);

            return ConfigurationTool.bindProperties(MuProcessManagementPolicy.class, policyProperties);
        }
        catch (IOException ioe) {
            String info = "Failed to load process management policy from \"" + file.getAbsolutePath() + "\": ";
            info += ioe.getMessage();
            throw new MuProcessException(info, ioe);
        }
    }

    public static MuProcessManagementPolicy getManagementPolicy(Class clazz, String resource) throws MuProcessException {
        if (null == clazz) {
            throw new IllegalArgumentException("class");
        }

        try (InputStream is = clazz.getResourceAsStream(resource)) {
            if (null == is) {
                String info = "Unknown resource: class=\"" + clazz.getName() + "\", resource=\"" + resource + "\"";
                throw new IllegalArgumentException(info);
            }
            final Properties policyProperties = new Properties();
            policyProperties.loadFromXML(is);

            return ConfigurationTool.bindProperties(MuProcessManagementPolicy.class, policyProperties);
        }
        catch (IOException ioe) {
            String info = "Failed to load process management policy: ";
            info += ioe.getMessage();
            throw new MuProcessException(info, ioe);
        }
    }

    public static MuProcessManagementPolicy getDefaultManagementPolicy() throws MuProcessException {
        return getManagementPolicy(MuProcessManager.class, "default-management-policy.xml");
    }
}
