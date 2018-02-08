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
package eu.ensure.muprocessmanager;

import eu.ensure.muprocessmanager.queue.WorkQueue;
import eu.ensure.muprocessmanager.queue.WorkerQueueFactory;
import eu.ensure.vopn.db.Database;
import eu.ensure.vopn.db.utils.Derby;
import eu.ensure.vopn.db.utils.Manager;
import eu.ensure.vopn.db.utils.Options;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.sql.DataSource;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.util.*;

public class MuProcessManager {
    private static final Logger log = LogManager.getLogger(MuProcessManager.class);

    //
    private final boolean acceptCompensationFailure = true;

    //
    private final static int DUMP_STATISTICS_PERIOD = 5000; // 5 * 60 * 1000; // Every 5 minutes
    private Timer dumpStatisticsTimer = null;

    //
    private final static int RECOVER_PROCESSES_PERIOD = 10000 ; // 1 * 60 * 1000; // Every minute
    private Timer recoverTimer = null;

    private final WorkQueue recoverWorkQueue;
    private final static int NUMBER_RECOVER_THREADS = 4;

    //
    private final long GENERAL_TIMEOUT_PERIOD = 20000; // 5 * 60 * 1000L;

    //
    private final MuPersistentLog compensationLog;
    private static final String APPLICATION_NAME = "mu_process_manager";
    private static final boolean DEBUG = false;


    private MuProcessManager(DataSource dataSource) {
        compensationLog = new MuPersistentLog(dataSource);
        recoverWorkQueue = WorkerQueueFactory.getWorkQueue(
                WorkerQueueFactory.Type.Multi,
                NUMBER_RECOVER_THREADS
        );
    }

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
            dumpStatisticsTimer.scheduleAtFixedRate(statisticsTask, initialDelay, DUMP_STATISTICS_PERIOD);
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
            recoverTimer.scheduleAtFixedRate(cleanupTask, initialDelay, RECOVER_PROCESSES_PERIOD);
        }
    }

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
        if (log.isTraceEnabled()) {
            String info = "Running scheduled recovery...";
            log.trace(info);
        }

        // Prepare collecting statistics for each state and operation
        final int numStates = MuProcessStatus.values().length;
        final long[] recoverCount = new long[numStates];
        final long[] removeCount = new long[numStates];
        final long[] abandonCount = new long[numStates];
        for (int i = 0; i < numStates; i++) {
            recoverCount[i] = removeCount[i] = abandonCount[i] = 0L;
        }

        final long[] observations = { 0L }; // in order to increment below

        //
        try {
            compensationLog.recover((correlationId, processId, status, created, modified) -> {
                observations[0]++; // explicit code
                MuProcessStatus _status = MuProcessStatus.fromInt(status);

                // Check if process has been stuck?
                Date now = new Date();
                Date then = new Date(now.getTime() - GENERAL_TIMEOUT_PERIOD);
                boolean hasTimedout = modified.before(then);

                //
                switch (_status) {
                    case NEW:
                    case PROGRESSING:
                        if (hasTimedout) {
                            if (log.isDebugEnabled()) {
                                String info = "Abandoning stuck process: correlationId=\"" + correlationId + "\", processId=\"" + processId + "\"";
                                log.debug(info);
                            }

                            recoverWorkQueue.execute(() -> {
                                // Since we don't have a micro process waiting, we will not propagate any
                                // exceptions
                                try {
                                    // Ignored returned exception -- we don't want to throw anything here
                                    MuProcess.compensate(compensationLog, correlationId, processId, acceptCompensationFailure);
                                    recoverCount[status]++;

                                } catch (MuProcessException unexpected) {
                                    if (log.isDebugEnabled()) {
                                        String info = "Failed to recover process: correlationId=\"" + correlationId + "\", processId=\"" + processId + "\": ";
                                        info += unexpected.getMessage();
                                        log.debug(info);
                                    }
                                }
                            });
                        }
                        break;

                    case SUCCESSFUL:
                    case COMPENSATED:
                        if (hasTimedout) {
                            // Automatically remove after timeout
                            if (log.isTraceEnabled()) {
                                String info = "Removing retired process: correlationId=\"" + correlationId + "\", processId=\"" + processId + "\"";
                                log.trace(info);
                            }
                            compensationLog.remove(correlationId, processId);
                            removeCount[status]++;
                        }
                        break;

                    case COMPENSATION_FAILED:
                        if (hasTimedout) {
                            if (log.isTraceEnabled()) {
                                String info = "Abandoning process: correlationId=\"" + correlationId + "\", processId=\"" + processId + "\"";
                                log.trace(info);
                            }
                            compensationLog.abandon(correlationId, processId);
                            abandonCount[status]++;

                        }
                        else {
                            if (log.isTraceEnabled()) {
                                String info = "Recovering process: correlationId=\"" + correlationId + "\", processId=\"" + processId + "\"";
                                log.trace(info);
                            }

                            recoverWorkQueue.execute(() -> {
                                // Since we don't have a micro process waiting, we will not propagate any
                                // exceptions
                                try {
                                    // Ignored returned exception -- we don't want to throw anything here
                                    MuProcess.compensate(compensationLog, correlationId, processId, acceptCompensationFailure);
                                    recoverCount[status]++;

                                } catch (MuProcessException unexpected) {
                                    if (log.isDebugEnabled()) {
                                        String info = "Failed to recover process: correlationId=\"" + correlationId + "\", processId=\"" + processId + "\": ";
                                        info += unexpected.getMessage();
                                        log.debug(info);
                                    }
                                }
                            });
                        }
                        break;

                    default:
                        // Do nothing!!!
                        break;
                }
            });
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
            MuProcessStatus status = MuProcessStatus.fromInt(i);
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

    public MuVolatileProcess newVolatileProcess() {
        return new MuVolatileProcess(acceptCompensationFailure);
    }

    public MuProcess newProcess(final String correlationId) {
        return new MuProcess(correlationId, compensationLog, acceptCompensationFailure);
    }

    public Optional<MuProcessStatus> getProcessStatus(final String correlationId) throws MuProcessException {
        return compensationLog.getProcessStatus(correlationId);
    }

    ///////////////////////////////////////////////////////////////////////////////////////////////////////////////

    public static MuProcessManager getManager(DataSource dataSource) {
        return new MuProcessManager(dataSource);
    }


    private static MuProcessManager getManager(Database.Configuration config) throws MuProcessException {
        try {
            DataSource dataSource = Derby.getDataSource(APPLICATION_NAME, config);

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

    public static MuProcessManager getManager() throws MuProcessException {
        Properties properties = new Properties();
        try {
            try (InputStream is = MuProcessManager.class.getResourceAsStream("database-configuration.xml")) {
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
     * <p/>
     *
     * @throws Exception
     */
    private void create(Manager manager, PrintWriter out) throws Exception {
        try (InputStream is = getClass().getResourceAsStream("create.sql")) {
            manager.execute("create.sql", new InputStreamReader(is), out);
        }
    }

    /**
     * Initiates the database with basic content.
     * <p/>
     *
     * @throws Exception
     */
    private void initiate(Manager manager, PrintWriter out) throws Exception {
        try (InputStream is = getClass().getResourceAsStream("initiate.sql")) {
            manager.execute("initiate.sql", new InputStreamReader(is), out);
        }
    }
}
