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

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import eu.ensure.muprocessmanager.utils.ActivityLoader;
import eu.ensure.vopn.db.Database;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.sql.DataSource;
import java.io.Reader;
import java.io.StringReader;
import java.lang.reflect.Method;
import java.sql.*;
import java.util.HashMap;
import java.util.Optional;
import java.util.Properties;

/**
 * Takes care of persisting compensations to a relational database and subsequently reading
 * process steps (activities) from the database. Currently only compensations are stored.
 * <p>
 * The DDL for this database is found in src/resources/eu/ensure/muprocessmanager/create.sql
 * <p>
 * Currently the DML-stuff is maintained internally in this class.
 */
public class MuPersistentLog {
    private static final Logger log = LogManager.getLogger(MuPersistentLog.class);
    private static final Logger statisticsLog = LogManager.getLogger("STATISTICS");

    private static final Gson gson = new GsonBuilder().disableHtmlEscaping().create();
    private static final ActivityLoader<MuBackwardBehaviour> loader = new ActivityLoader<>("compensation activity");

    private final DataSource dataSource;
    private final Properties sqlStatements;

    private final HashMap<String, Long> sqlStatementCount = new HashMap<>();

    private final static int STATUS_SUCCESSFUL = MuProcessStatus.SUCCESSFUL.ordinal();

    public interface CompensationRunnable {
        boolean run(MuBackwardBehaviour activity, Method method, MuActivityParameters parameters, int step, int retries) throws MuProcessBackwardBehaviourException;
    }

    public interface CleanupRunnable {
        void run(String correlationId, int processId, int status, java.util.Date created, java.util.Date modified) throws MuProcessException;
    }

    /* package private */  MuPersistentLog(final DataSource dataSource, final Properties sqlStatements) {
        this.dataSource = dataSource;
        this.sqlStatements = sqlStatements;
    }

    static int i = 0;

    private String getStatement(String key) throws MuProcessException {

        String statement = sqlStatements.getProperty(key);
        if (null == statement || statement.length() == 0) {
            String info = "No SQL statement matching key=\"" + key + "\"";
            throw new MuProcessException(info);
        }

        // Used during development to prune unused statements and otherwise
        // harmless since constantly false conditional blocks are removed at
        // compile time (as per the Java specification)
        if (false) {
            sqlStatementCount.put(key, 1L + sqlStatementCount.getOrDefault(key, 0L));

            if (++i % 100000 == 0) {
                sqlStatements.forEach((k, s) -> {
                    if (!sqlStatementCount.containsKey(k)) {
                        System.out.println("Unused statement (sofar): " + k);
                    }
                });
            }
        }
        return statement;
    }


    /**
     * Logs MuProcess to database, mutating MuProcess by setting processId after persisting (and allocating identity).
     * @param process
     * @return
     * @throws MuProcessException
     */
    /* package private */ int pushProcess(
            final MuProcess process
    ) throws MuProcessException {
        if (log.isTraceEnabled()) {
            String info = "Persisting process (" + process.getProcessId() + ")";
            log.trace(info);
        }

        try (Connection conn = dataSource.getConnection()) {

            try (PreparedStatement stmt = conn.prepareStatement(getStatement("STORE_PROCESS"), Statement.RETURN_GENERATED_KEYS)) {
                stmt.setString(1, process.getCorrelationId());
                stmt.setInt(2, MuProcessStatus.NEW.toInt());
                int i = stmt.executeUpdate();
                try (ResultSet rs = stmt.getGeneratedKeys()) {
                    if (rs.next()) {
                        int processId = rs.getInt(1);
                        process.setProcessId(processId);
                        return processId;
                    }
                    else {
                        String info = "Failed to determine auto-generated process id";
                        throw new MuProcessException(info);
                    }
                }
            }
        }
        catch (SQLException sqle) {
            String info = "Failed to persist process header for correlationId \"" + process.getCorrelationId() + "\": ";
            info += Database.squeeze(sqle);
            log.warn(info, sqle);
            throw new MuProcessException(info, sqle);
        }
    }

    /* package private */ Optional<MuProcessStatus> getProcessStatus(
            final String correlationId
    ) throws MuProcessException {
        try (Connection conn = dataSource.getConnection()) {
            try (PreparedStatement stmt = conn.prepareStatement(getStatement("FETCH_PROCESS_STATUS_BY_CORRID"))) {
                stmt.setString(1, correlationId);

                try (ResultSet rs = stmt.executeQuery()) {
                    if (rs.next()) {
                        // status
                        int status = rs.getInt(1);
                        return Optional.of(MuProcessStatus.fromInt(status));
                    }
                }
            }
        }
        catch (SQLException sqle) {
            String info = "Failed to query process status: ";
            info += Database.squeeze(sqle);
            log.warn(info);
            throw new MuProcessException(info, sqle);
        }

        return Optional.empty();
    }

    /* package private */ Optional<MuProcessResult> getProcessResult(
            final String correlationId
    ) throws MuProcessException {
        try (Connection conn = dataSource.getConnection()) {
            try (PreparedStatement stmt = conn.prepareStatement(getStatement("FETCH_PROCESS_RESULT_BY_CORRID"))) {
                stmt.setString(1, correlationId);

                try (ResultSet rs = stmt.executeQuery()) {
                    if (rs.next()) {
                        // status, result
                        int idx = 0;
                        int status = rs.getInt(++idx);

                        if (STATUS_SUCCESSFUL != status) {
                            String info = "Results only available for SUCCESSFUL processes: ";
                            info += "correlationId=\"" + correlationId + "\" ";
                            info += "processStatus=\"" + MuProcessStatus.fromInt(status) + "\"";
                            throw new MuProcessResultsUnavailable(info);
                        }

                        Clob resultClob = rs.getClob(++idx);
                        if (!rs.wasNull()) {
                            return Optional.of(MuProcessResult.fromReader(resultClob.getCharacterStream()));
                        }
                    }
                }
            }
        }
        catch (SQLException sqle) {
            String info = "Failed to fetch process result: ";
            info += Database.squeeze(sqle);
            log.warn(info);
            throw new MuProcessException(info, sqle);
        }

        return Optional.empty();
    }

    /* package private */ void setProcessStatus(
            final int processId, final MuProcessStatus status, final MuProcessResult result
    ) throws MuProcessException {

        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);

            try (PreparedStatement stmt = conn.prepareStatement(getStatement("UPDATE_PROCESS"))) {
                int idx = 0;
                stmt.setInt(++idx, status.toInt());
                if (null != result) {
                    stmt.setCharacterStream(++idx, new StringReader(gson.toJson(result)));
                }
                else {
                    stmt.setNull(++idx, Types.CLOB);
                }
                stmt.setInt(++idx, processId);
                stmt.executeUpdate();
            }

            conn.commit();
        }
        catch (SQLException sqle) {
            String info = "Failed to set process status: ";
            info += Database.squeeze(sqle);
            log.warn(info);
            throw new MuProcessException(info, sqle);
        }
    }

    /* package private */ void setProcessStatus(
            final int processId, final MuProcessStatus status
    ) throws MuProcessException {
        setProcessStatus(processId, status, /* no result */ null);
    }

    /* package private */ void markRetry(
        final int processId, final int stepId
    ) throws MuProcessException {

        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);

            try (PreparedStatement stmt = conn.prepareStatement(getStatement("INCREMENT_PROCESS_STEP_RETRIES"))) {
                int idx = 0;
                stmt.setInt(++idx, processId);
                stmt.setInt(++idx, stepId);
                stmt.executeUpdate();
            }

            conn.commit();
        }
        catch (SQLException sqle) {
            String info = "Failed to increment process step retries: ";
            info += Database.squeeze(sqle);
            log.warn(info);
            throw new MuProcessException(info, sqle);
        }
    }

    /* package private */ void compensate(
            final int processId, final CompensationRunnable runnable
    ) throws MuProcessException {

        try (Connection conn = dataSource.getConnection()) {
            try (PreparedStatement stmt = conn.prepareStatement(getStatement("FETCH_PROCESS_STEPS"))) {
                stmt.setInt(1, processId);
                try (ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        // correlation_id, step_id, class_name, method_name, parameters, retries
                        int idx = 0;
                        String correlationId = rs.getString(++idx);
                        int stepId = rs.getInt(++idx);
                        String className = rs.getString(++idx);
                        String methodName = rs.getString(++idx);
                        Clob paramClob = rs.getClob(++idx);
                        int retries = rs.getInt(++idx);

                        //
                        MuBackwardBehaviour activity = loader.load(className);
                        if (activity != null) {
                            Class[] parameterTypes = { MuActivityParameters.class };
                            Method method = loader.createMethod(activity, methodName, parameterTypes);

                            Reader paramReader = paramClob.getCharacterStream();
                            MuActivityParameters parameters = gson.fromJson(paramReader, MuActivityParameters.class);
                            if (runnable.run(activity, method, parameters, stepId, retries)) {
                                popCompensation(processId, stepId);
                            }
                            else {
                                markRetry(processId, stepId);
                            }
                        }
                        else {
                            String info = "Failed to compensate process (correlationId=\"" + correlationId + "\", processId=\"" + processId + "\",  stepId=" + stepId + "): ";
                            info += "Not a MuBackwardActivity! " + className;
                            throw new MuProcessBackwardBehaviourException(info);
                        }
                    }
                }
            }
        }
        catch (SQLException sqle) {
            String info = "Failed to query compensation: ";
            info += Database.squeeze(sqle);
            throw new MuProcessBackwardBehaviourException(info, sqle);
        }
        catch (ClassNotFoundException cnfe) {
            String info = "Failed to instantiate compensation: ";
            info += cnfe.getMessage();
            throw new MuProcessBackwardBehaviourException(info, cnfe);
        }
        catch (NoSuchMethodException nsme) {
            String info = "Failed to establish call endpoint for compensation: ";
            info += nsme.getMessage();
            throw new MuProcessBackwardBehaviourException(info, nsme);
        }
    }

    /* package private */ void cleanupAfterSuccess(
            final int processId, final MuProcessResult result
    ) throws MuProcessException {

        // Remove process steps
        try (Connection conn = dataSource.getConnection()) {
            try (PreparedStatement stmt = conn.prepareStatement(getStatement("FETCH_PROCESS_STEPS"))) {
                stmt.setInt(1, processId);
                try (ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        // correlation_id, step_id, class_name, method_name, parameters, retries
                        int stepId = rs.getInt(2);
                        popCompensation(processId, stepId);
                    }
                }
            }
        }
        catch (SQLException sqle) {
            String info = "Failed to query compensation: ";
            info += Database.squeeze(sqle);
            throw new MuProcessBackwardBehaviourException(info, sqle);
        }
        finally {
            // Set process status
            setProcessStatus(processId, MuProcessStatus.SUCCESSFUL, result);
        }
    }

    /* package private */ void cleanupAfterSuccessfulCompensation(
            final int processId
    ) throws MuProcessException {
        setProcessStatus(processId, MuProcessStatus.COMPENSATED);
    }

    /* package private */ void cleanupAfterFailedCompensation(
            final int processId
    ) throws MuProcessException {
        setProcessStatus(processId, MuProcessStatus.COMPENSATION_FAILED);
    }

    /* package private */ void cleanupAfterFailure(
            final int processId
    ) throws MuProcessException {
        setProcessStatus(processId, MuProcessStatus.ABANDONED);
    }

    /* package private */ void dumpStatistics() {
        // Prepare collecting statistics for each state
        final int numStates = MuProcessStatus.values().length;
        long[] statusCount = new long[numStates];
        for (int i = 0; i < numStates; i++) {
            statusCount[i] = 0L;
        }

        //
        try (Connection conn = dataSource.getConnection()) {
            try (PreparedStatement stmt = conn.prepareStatement(getStatement("COUNT_PROCESSES"))) {
                try (ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        // count, status
                        int count = rs.getInt(1);
                        int status = rs.getInt(2);

                        // Assemble some statistics
                        if (status >= 0 && status < numStates) { // as it should be
                            statusCount[status] = count;
                        }
                    }
                }
            }
        }
        catch (SQLException sqle) {
            String info = "Failed to count process headers: ";
            info += Database.squeeze(sqle);
            log.warn(info);
        }
        catch (MuProcessException mpe) {
            String info = "Cannot dump statistics: ";
            info += mpe.getMessage();
            log.warn(info);
        }

        // Do some reporting
        boolean haveSomethingToDisplay = false;
        StringBuilder statistics = new StringBuilder();
        long total = 0L;
        for (int i = 0; i < numStates; i++) {
            long count = statusCount[i];
            total += count;

            MuProcessStatus status = MuProcessStatus.fromInt(i);
            if (count > 0) {
                statistics.append("{").append(count).append(" ").append(status).append("} ");
                haveSomethingToDisplay = true;
            }
        }
        statistics.append("{").append(total).append(" in total}");

        if (haveSomethingToDisplay) {
            statisticsLog.info(statistics.toString());
        }
    }

    /* package private */ void recover(
            final CleanupRunnable runnable
    ) throws MuProcessException {
        try (Connection conn = dataSource.getConnection()) {
            try (PreparedStatement stmt = conn.prepareStatement(getStatement("FETCH_PROCESSES"))) {
                try (ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        // correlation_id, process_id, status, created, modified
                        int idx = 0;
                        String correlationId = rs.getString(++idx);
                        int processId = rs.getInt(++idx);
                        int status = rs.getInt(++idx);
                        Timestamp created = rs.getTimestamp(++idx);
                        Timestamp modified = rs.getTimestamp(++idx);

                        runnable.run(correlationId, processId, status, created, modified);
                    }
                }
            }
        }
        catch (SQLException sqle) {
            String info = "Failed to query process headers: ";
            info += Database.squeeze(sqle);
            log.warn(info);
        }
    }

    /* package private */ void abandon(String correlationId, int processId) throws MuProcessException {
        if (log.isTraceEnabled()) {
            String info = "Abandoning process: correlationId=\"" + correlationId + "\", processId=\"" + processId + "\": ";
            log.trace(info);
        }
        setProcessStatus(processId, MuProcessStatus.ABANDONED);
    }

    /* package private */ void remove(String correlationId, int processId) throws MuProcessException {
        if (log.isTraceEnabled()) {
            String info = "Removing process: correlationId=\"" + correlationId + "\", processId=\"" + processId + "\": ";
            log.trace(info);
        }

        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);

            try (PreparedStatement stmt = conn.prepareStatement(getStatement("REMOVE_PROCESS_STEPS"))) {
                stmt.setInt(1, processId);
                stmt.executeUpdate();
            }

            try (PreparedStatement stmt = conn.prepareStatement(getStatement("REMOVE_PROCESS"))) {
                stmt.setInt(1, processId);
                stmt.executeUpdate();
            }

            conn.commit();

        } catch (SQLException sqle) {
            String info = "Failed to remove process: correlationId=\"" + correlationId + "\", processId=\"" + processId + "\": ";
            info += Database.squeeze(sqle);
            log.warn(info);
            throw new MuProcessException(info, sqle);
        }
    }

    ///////////////////////////////////////////////////////////////////////////////////////////////////////////////
    //    Methods called from MuProcess and tightly integrated with the MuProcess lifecycle
    ///////////////////////////////////////////////////////////////////////////////////////////////////////////////

    /* package private */ void pushCompensation(
            final MuProcess process, final MuBackwardBehaviour activity,
            final MuActivityParameters parameters
    ) throws MuProcessException {

        // Determine class name
        Class<? extends MuBackwardBehaviour> clazz = activity.getClass();
        String className = clazz.getName();

        // Check existence of persistable method name
        String methodName = activity.getPersistableMethodName();

        try {
            clazz.getMethod(activity.getPersistableMethodName(), MuActivityParameters.class);
        }
        catch (NoSuchMethodException nsme) {
            // Not ever expected to happen in production! Can potentially happen in development though,
            // so it is relevant to prominently log this
            String info = "Failed to validate backward activity method (" + methodName + ") in class (" + className + "): ";
            log.warn(info, nsme);
            throw new MuProcessException(info);
        }

        // Persist
        if (0 == process.incrementCurrentStep()) {
            // Log process header
            pushProcess(process);
        }

        if (log.isTraceEnabled()) {
            String info = "Persisting process step (" + process.getProcessId() + ")[";
            info += process.getCurrentStep();
            info += "] class=" + className;
            info += " method=" + methodName;
            log.trace(info);
        }

        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);

            try (PreparedStatement stmt = conn.prepareStatement(getStatement("STORE_PROCESS_STEP"))) {
                int idx = 0;
                stmt.setInt(++idx, process.getProcessId());
                stmt.setInt(++idx, process.getCurrentStep());
                stmt.setString(++idx, className);
                stmt.setString(++idx, methodName);
                stmt.setCharacterStream(++idx, new StringReader(gson.toJson(parameters)));
                stmt.executeUpdate();
            }

            // Potentially check whether process status is NEW or (already) PROGRESSING
            try (PreparedStatement stmt = conn.prepareStatement(getStatement("UPDATE_PROCESS"))) {
                int idx = 0;
                stmt.setInt(++idx, MuProcessStatus.PROGRESSING.toInt());
                stmt.setNull(++idx, Types.CLOB);
                stmt.setInt(++idx, process.getProcessId());
                stmt.executeUpdate();
            }

            conn.commit();
        }
        catch (SQLException sqle) {
            String info = "Failed to persist process step: ";
            info += Database.squeeze(sqle);
            log.warn(info);
            throw new MuProcessException(info, sqle);
        }
    }

    private void popCompensation(
            final int processId, final int stepId
    ) throws MuProcessException {

        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);

            try (PreparedStatement stmt = conn.prepareStatement(getStatement("REMOVE_PROCESS_STEP"))) {
                int idx = 0;
                stmt.setInt(++idx, processId);
                stmt.setInt(++idx, stepId);
                stmt.executeUpdate();
            }
            conn.commit();
        }
        catch (SQLException sqle) {
            String info = "Failed to remove process step: ";
            info += Database.squeeze(sqle);
            log.warn(info);
            throw new MuProcessBackwardBehaviourException(info, sqle);
        }
    }
}
