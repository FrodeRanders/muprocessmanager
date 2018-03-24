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

import org.gautelis.muprocessmanager.payload.*;
import org.gautelis.vopn.db.Database;
import org.gautelis.vopn.lang.DynamicLoader;
import org.gautelis.vopn.queue.WorkQueue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.io.Reader;
import java.lang.reflect.Method;
import java.sql.*;
import java.util.*;

/**
 * Takes care of persisting compensations to a relational database and subsequently reading
 * process steps (activities) from the database. Currently compensations are stored (together
 * with the corresponding parameters) as well as {@link MuProcessState#SUCCESSFUL SUCCESSFUL}
 * results.
 * <p>
 * The DDL is found in src/resources/org/gautelis/muprocessmanager/default-database-create.sql.
 * <p>
 * The DML is found in src/resources/org/gautelis/muprocessmanager/sql-statements.xml.
 */
public class MuPersistentLog {
    private static final Logger log = LoggerFactory.getLogger(MuPersistentLog.class);
    private static final Logger statisticsLog = LoggerFactory.getLogger("STATISTICS");

    private static final DynamicLoader<MuBackwardBehaviour> loader = new DynamicLoader<>("compensation activity");

    private final DataSource dataSource;
    private final Properties sqlStatements;

    private final HashMap<String, Long> sqlStatementCount = new HashMap<>();

    private final static int STATE_SUCCESSFUL = MuProcessState.SUCCESSFUL.ordinal();
    private final boolean assumeNativeProcessDataFlow;

    public interface CompensationRunnable {
        boolean run(MuBackwardBehaviour activity, Method method, MuBackwardActivityContext context, int step, int retries) throws MuProcessBackwardBehaviourException;
    }

    public interface CleanupRunnable {
        void run(String correlationId, int processId, int state, java.util.Date created, java.util.Date modified) throws MuProcessException;
    }

    /* package private */  MuPersistentLog(final DataSource dataSource, final Properties sqlStatements, final boolean assumeNativeProcessDataFlow) {
        this.dataSource = dataSource;
        this.sqlStatements = sqlStatements;
        this.assumeNativeProcessDataFlow = assumeNativeProcessDataFlow;
    }

    private int i = 0; // for development purposes -- ignore please :)

    private String getStatement(String key) throws MuProcessException {

        String statement = sqlStatements.getProperty(key);
        if (null == statement || statement.length() == 0) {
            /*
             * Make sure we catch this during development!!!
             */
            String info = "No SQL statement matching key=\"" + key + "\"";
            MuProcessException syntheticException = new MuProcessException(info);
            log.error(info, syntheticException);
            syntheticException.printStackTrace(System.err);
            throw syntheticException;
        }

        if (false) {
            //---------------------------------------------------------------------------
            // Used during development to prune unused statements and otherwise
            // harmless since constantly false conditional blocks are removed at
            // compile time (as per the Java specification)
            //---------------------------------------------------------------------------
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
     * Logs {@link MuProcess} to database, mutating {@link MuProcess} by setting processId
     * after persisting (and allocating identity).
     * @param process the process to persist.
     * @return the processId of the (new) process.
     * @throws MuProcessException
     */
    /* package private */ int pushProcess(
            final MuProcess process
    ) throws MuProcessException {
        try (Connection conn = dataSource.getConnection()) {
            try (PreparedStatement stmt = conn.prepareStatement(getStatement("STORE_PROCESS"), Statement.RETURN_GENERATED_KEYS)) {
                stmt.setString(1, process.getCorrelationId());
                stmt.setInt(2, MuProcessState.NEW.toInt());
                Database.executeUpdate(stmt);

                try (ResultSet rs = stmt.getGeneratedKeys()) {
                    if (rs.next()) {
                        int processId = rs.getInt(1);
                        process.setProcessId(processId);

                        log.trace("Persisted process: correlationId=\"{}\", processId={}", process.getCorrelationId(), processId);
                        return processId;
                    }
                    else {
                        String info = "Failed to determine auto-generated process id";
                        log.error(info); // This is nothing we can recover from
                        throw new MuProcessException(info);
                    }
                }
            }
        }
        catch (SQLException sqle) {
            /*
             * Failed to persist process header for correlationId "775113c6-8f7a-4f0d-b5fd-9139727ef227":
             * DerbySQLIntegrityConstraintViolationException [
             *    The statement was aborted because it would have caused a duplicate key value in a unique
             *    or primary key constraint or unique index identified by 'MU_PROCESS_CORRID_IX' defined
             *    on 'MU_PROCESS'.
             * ], SQLstate(23505), Vendor code(30000)
             */

            // State: 23xyz - Integrity constraint/key violation
            //  [SQL Server: Data already exists]
            //  [Oracle:     Data already exists]
            //  [DB2:        Constraint violation]
            if (sqle.getSQLState().startsWith("23")) {
                String info = "A process already exists for this business request: correlation ID \"" + process.getCorrelationId() + "\"";
                log.trace(info);
                throw new MuProcessAlreadyExistsException(info, sqle);
            }
            else {
                String info = "Failed to persist process header for correlationId \"" + process.getCorrelationId() + "\": ";
                info += Database.squeeze(sqle);
                log.warn(info, sqle);
                throw new MuProcessException(info, sqle);
            }
        }
    }

    /* package private */ Optional<MuProcessState> getProcessState(
            final String correlationId
    ) throws MuProcessException {
        try (Connection conn = dataSource.getConnection()) {
            try (PreparedStatement stmt = conn.prepareStatement(
                    getStatement("FETCH_PROCESS_STATE_BY_CORRID"),
                    ResultSet.TYPE_SCROLL_INSENSITIVE, ResultSet.CONCUR_READ_ONLY)
            ) {
                stmt.setString(1, correlationId);

                try (ResultSet rs = Database.executeQuery(stmt)) {
                    if (rs.next()) {
                        // state
                        int state = rs.getInt(1);
                        return Optional.of(MuProcessState.fromInt(state));
                    }
                }
            }
        }
        catch (SQLException sqle) {
            String info = "Failed to query process state: ";
            info += Database.squeeze(sqle);
            log.warn(info, sqle);
            throw new MuProcessException(info, sqle);
        }

        return Optional.empty();
    }

    /* package private */ Optional<MuProcessResult> getProcessResult(
            final String correlationId
    ) throws MuProcessException {
        try (Connection conn = dataSource.getConnection()) {
            try (PreparedStatement stmt = conn.prepareStatement(
                    getStatement("FETCH_PROCESS_RESULT_BY_CORRID"),
                    ResultSet.TYPE_SCROLL_INSENSITIVE, ResultSet.CONCUR_READ_ONLY)
            ) {
                stmt.setString(1, correlationId);

                try (ResultSet rs = Database.executeQuery(stmt)) {
                    if (rs.next()) {
                        // state, result
                        int idx = 0;
                        int state = rs.getInt(++idx);

                        if (STATE_SUCCESSFUL != state) {
                            String info = "Results only available for SUCCESSFUL processes: ";
                            info += "correlationId=\"" + correlationId + "\" ";
                            info += "processState=\"" + MuProcessState.fromInt(state) + "\"";
                            throw new MuProcessResultsUnavailable(info);
                        }

                        Reader result = rs.getCharacterStream(++idx);
                        if (!rs.wasNull()) {
                            if (assumeNativeProcessDataFlow) {
                                return Optional.of(MuNativeProcessResult.fromReader(result));
                            }
                            else {
                                return Optional.of(MuForeignProcessResult.fromReader(result));
                            }
                        }
                    }
                }
            }
        }
        catch (SQLException sqle) {
            String info = "Failed to fetch process result: ";
            info += Database.squeeze(sqle);
            log.warn(info, sqle);
            throw new MuProcessException(info, sqle);
        }

        return Optional.empty();
    }

    /* package private */ void setProcessState(
            final int processId, final MuProcessState state, final MuProcessResult result
    ) throws MuProcessException {

        try (Connection conn = dataSource.getConnection()) {
            try (PreparedStatement stmt = conn.prepareStatement(getStatement("UPDATE_PROCESS"))) {
                int idx = 0;
                stmt.setInt(++idx, state.toInt());
                if (null == result || result.isEmpty()) {
                    stmt.setNull(++idx, Types.CLOB);
                }
                else {
                    // No need to explicitly Cloner.clone() result, since we
                    // are implicitly cloning by persisting to database.
                    stmt.setCharacterStream(++idx, result.toReader());
                }
                stmt.setInt(++idx, processId);
                Database.executeUpdate(stmt);
            }
        }
        catch (SQLException sqle) {
            String info = "Failed to set process state: ";
            info += Database.squeeze(sqle);
            log.warn(info, sqle);
            throw new MuProcessException(info, sqle);
        }

        log.trace("Updated process {} with state {}", processId, state);
    }

    /* package private */ void setProcessState(
            final int processId, final MuProcessState state
    ) throws MuProcessException {
        setProcessState(processId, state, /* no result */ null);
    }

    /* package private */ Optional<Boolean> resetProcess(final String correlationId) throws MuProcessException {
        Connection conn = null;
        try {
            conn = dataSource.getConnection();
            conn.setAutoCommit(false);

            int processId = MuProcess.PROCESS_ID_NOT_YET_ASSIGNED;
            MuProcessState state = null;
            boolean doContinue = true;

            try (PreparedStatement stmt = conn.prepareStatement(
                    getStatement("FETCH_PROCESS_ID_AND_STATE_BY_CORRID"),
                    ResultSet.TYPE_SCROLL_INSENSITIVE, ResultSet.CONCUR_READ_ONLY)
            ) {
                stmt.setString(1, correlationId);

                try (ResultSet rs = Database.executeQuery(stmt)) {
                    if (rs.next()) {
                        int idx = 0;
                        processId = rs.getInt(++idx);
                        state = MuProcessState.fromInt(rs.getInt(++idx));

                        switch (state) {
                            /*
                             * Cases where it does _not_ make sense to reset process -- at least not right away.
                             *
                             * OBSERVE FALLTHROUGH!
                             */
                            case NEW:
                                // PRESUMED NOT OK, may be interrupting a running synchronous process

                            case PROGRESSING:
                                // NOT OK, may be interrupting a running synchronous process

                            case SUCCESSFUL:
                                // Really odd situation! Why reset (for retrying) when process
                                // was successful?

                                log.warn("Will NOT reset process: correlationId=\"{}\", processId={}, state={}",
                                        correlationId, processId, state);
                                doContinue = false;
                                break;

                            default:
                                break;
                        }
                    }
                }
            }

            if (processId == MuProcess.PROCESS_ID_NOT_YET_ASSIGNED) {
                return Optional.empty();
            }

            if (!doContinue) {
                return Optional.of(false);
            }

            try (PreparedStatement stmt = conn.prepareStatement(
                    getStatement("FETCH_PROCESS_STEPS_BY_PROCID_COARSE"),
                    ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_UPDATABLE)
            ) {
                stmt.setInt(1, processId);

                try (ResultSet rs = Database.executeQuery(stmt)) {
                    while (rs.next()) {
                        /*
                         * 'process_id' need to be selected even though we know it, since we are using a updateable
                         *  result set. At least this is a requirement on PostgreSQL:
                         *    "ResultSet is not updateable.  The query that generated this result set must select
                         *    only one table, and must select all primary keys from that table. See the JDBC 2.1
                         *    API Specification, section 5.6 for more details."
                         *
                         *  We read the process_id field, so as to not haphazardly 'optimize' the SQL statement
                         *  away (since we seemingly don't need it)
                         */
                        int idx = 0;
                        int ignored = rs.getInt(++idx);
                        int stepId = rs.getInt(++idx);
                        int retries = rs.getInt(++idx);

                        //
                        log.trace("Removing process step (on reset): correlationId=\"{}\", processId={}, state={}, stepId={}, retries={}",
                                correlationId, processId, state, stepId, retries);

                        rs.deleteRow();
                    }
                }
            }

            try (PreparedStatement stmt = conn.prepareStatement(getStatement("REMOVE_PROCESS"))) {
                stmt.setInt(1, processId);
                Database.executeUpdate(stmt);
            }

            conn.commit();

            return Optional.of(true);
        }
        catch (SQLException sqle) {
            try {
                if (null != conn) conn.rollback();
            }
            catch (SQLException ignore) {}

            String info = "Failed to reset process: ";
            info += Database.squeeze(sqle);
            log.warn(info, sqle);
            throw new MuProcessException(info, sqle);
        }
        finally {
            try {
                if (null != conn) conn.close();
            }
            catch (SQLException ignore) {}
        }
    }

    /* package private */ Collection<MuProcessDetails> salvageAbandonedProcesses() throws MuProcessException {
        List<MuProcessDetails> detailsList = new LinkedList<>();

        try (Connection conn = dataSource.getConnection()) {
            try (PreparedStatement stmt = conn.prepareStatement(
                    getStatement("FETCH_ABANDONED_PROCESS_DETAILS"),
                    ResultSet.TYPE_SCROLL_INSENSITIVE, ResultSet.CONCUR_READ_ONLY)
            ) {
                try (ResultSet rs = Database.executeQuery(stmt)) {
                    MuProcessDetails details = null;
                    while (rs.next()) {
                        // correlation_id, process_id, state, p.created, p.modified, step_id, retries, preState
                        int idx = 0;

                        // Process related
                        String correlationId = rs.getString(++idx);
                        int processId = rs.getInt(++idx);
                        MuProcessState state = MuProcessState.fromInt(rs.getInt(++idx));
                        Timestamp created = rs.getTimestamp(++idx);
                        Timestamp modified = rs.getTimestamp(++idx);

                        if (null == details || details.getProcessId() != processId) {
                            // New process
                            details = new MuProcessDetails(correlationId, processId, state, created, modified);
                            detailsList.add(details);
                        }

                        // Process step related
                        int stepId = rs.getInt(++idx);
                        if (rs.wasNull()) {
                            // Then there are no steps associated with process.
                            // This is an effect of the left outer join.
                            continue;
                        }
                        int retries = rs.getInt(++idx);

                        MuActivityState preState = null;
                        Reader preStateReader = rs.getCharacterStream(++idx);
                        if (!rs.wasNull()) {
                            if (assumeNativeProcessDataFlow) {
                                preState = MuNativeActivityState.fromReader(preStateReader);
                            }
                            else {
                                preState = MuForeignActivityState.fromReader(preStateReader);
                            }
                        }

                        details.addActivityDetails(stepId, retries, preState);
                    }
                }
            }
        }
        catch (SQLException sqle) {
            String info = "Failed to fetch abandoned process details: ";
            info += Database.squeeze(sqle);
            log.warn(info, sqle);
            throw new MuProcessException(info, sqle);
        }

        return detailsList;
    }


    /* package private */ void markRetry(
        final int processId, final int stepId
    ) throws MuProcessException {

        try (Connection conn = dataSource.getConnection()) {
            try (PreparedStatement stmt = conn.prepareStatement(getStatement("INCREMENT_PROCESS_STEP_RETRIES"))) {
                int idx = 0;
                stmt.setInt(++idx, processId);
                stmt.setInt(++idx, stepId);
                Database.executeUpdate(stmt);
            }
        }
        catch (SQLException sqle) {
            String info = "Failed to increment process step retries: ";
            info += Database.squeeze(sqle);
            log.warn(info, sqle);
            throw new MuProcessException(info, sqle);
        }
    }

    /* package private */ void compensate(
            final int processId, final CompensationRunnable runnable
    ) throws MuProcessException {

        try (Connection conn = dataSource.getConnection()) {
            try (PreparedStatement stmt = conn.prepareStatement(
                    getStatement("FETCH_PROCESS_STEPS_BY_PROCID_DETAILED"),
                    ResultSet.TYPE_SCROLL_INSENSITIVE, ResultSet.CONCUR_READ_ONLY)
            ) {
                stmt.setInt(1, processId);
                try (ResultSet rs = Database.executeQuery(stmt)) {
                    while (rs.next()) {
                        // correlation_id, step_id, class_name, method_name, activity_params, orchestr_params, retries, previous_state
                        int idx = 0;
                        String correlationId = rs.getString(++idx);
                        int stepId = rs.getInt(++idx);
                        String className = rs.getString(++idx);
                        String methodName = rs.getString(++idx);

                        // activity parameters
                        //   We need to consume the character stream right away, since the next call to
                        //   rs.getCharacterStream() may effectively sabotage it's state. This is the
                        //   case with the Derby JDBC implementation (but not with the PostgreSQL version).
                        MuActivityParameters activityParameters;
                        Reader activityParamReader = rs.getCharacterStream(++idx);
                        if (!rs.wasNull()) {
                            if (assumeNativeProcessDataFlow) {
                                activityParameters = MuNativeActivityParameters.fromReader(activityParamReader);
                            }
                            else {
                                activityParameters = MuForeignActivityParameters.fromReader(activityParamReader);
                            }
                        }
                        else {
                            activityParameters = new MuNoActivityParameters();
                        }

                        // orchestration parameters
                        //   Consume the stream
                        MuOrchestrationParameters orchestrationParameters = null;
                        Reader orchestrationParamReader = rs.getCharacterStream(++idx);
                        if (!rs.wasNull()) {
                            orchestrationParameters =
                                    MuOrchestrationParameters.fromReader(orchestrationParamReader);
                        }

                        //
                        int retries = rs.getInt(++idx);

                        // pre-state
                        //   Consume the stream
                        MuActivityState preState = null;
                        Reader stateReader = rs.getCharacterStream(++idx);
                        if (!rs.wasNull()) {
                            if (assumeNativeProcessDataFlow) {
                                preState = MuNativeActivityState.fromReader(stateReader);
                            }
                            else {
                                preState = MuForeignActivityState.fromReader(stateReader);
                            }
                        }

                        //
                        MuBackwardBehaviour activity = loader.load(className);
                        if (activity != null) {
                            if (false) {
                                 //---------------------------------------------------------------------------
                                 // Used during development to trap inadvertent changes to signature of
                                 // MuBackwardBehaviour#backward, since we have a non-compile time detectable
                                 // dependency below. This if-statement is never meant to be run but is
                                 // harmless since constantly false conditional blocks are removed at
                                 // compile time (as per the Java specification)
                                 //---------------------------------------------------------------------------
                                MuBackwardBehaviour trapChangesToInterface = new MuBackwardBehaviour() {
                                    @Override
                                    public boolean backward(MuBackwardActivityContext context) {
                                        return false;
                                    }
                                };
                            }
                            Class[] parameterTypes = { MuBackwardActivityContext.class };
                            Method method = loader.createMethod(activity, methodName, parameterTypes);

                            MuBackwardActivityContext context =
                                    new MuBackwardActivityContext(correlationId, activityParameters, orchestrationParameters, preState);

                            //
                            if (runnable.run(activity, method, context, stepId, retries)) {
                                popCompensation(conn, processId, stepId);
                            }
                            else {
                                markRetry(processId, stepId);
                            }
                        }
                        else {
                            String info = "Failed to compensate process (correlationId=\"" + correlationId + "\", processId=" + processId + ",  stepId=" + stepId + "): ";
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
            log.warn(info, sqle);
            throw new MuProcessBackwardBehaviourException(info, sqle);
        }
        catch (ClassNotFoundException cnfe) {
            String info = "Failed to instantiate compensation: ";
            info += cnfe.getMessage();
            log.info(info);
            throw new MuProcessBackwardBehaviourException(info, cnfe);
        }
        catch (NoSuchMethodException nsme) {
            String info = "Failed to establish call endpoint for compensation: ";
            info += nsme.getMessage();
            log.info(info);
            throw new MuProcessBackwardBehaviourException(info, nsme);
        }
    }

    /* package private */ void cleanupAfterSuccess(
            final int processId, final MuProcessResult result
    ) throws MuProcessException {

        // Remove process steps
        try (Connection conn = dataSource.getConnection()) {
            try (PreparedStatement stmt = conn.prepareStatement(
                    getStatement("FETCH_PROCESS_STEPS_BY_PROCID_COARSE"),
                    ResultSet.TYPE_SCROLL_INSENSITIVE, ResultSet.CONCUR_READ_ONLY)
            ) {
                stmt.setInt(1, processId);
                try (ResultSet rs = Database.executeQuery(stmt)) {
                    while (rs.next()) {
                        // process_id, step_id, retries
                        int stepId = rs.getInt(2);
                        popCompensation(conn, processId, stepId);
                    }
                }
            }
        }
        catch (SQLException sqle) {
            String info = "Failed to query compensation: ";
            info += Database.squeeze(sqle);
            log.warn(info, sqle);
            throw new MuProcessBackwardBehaviourException(info, sqle);
        }
        finally {
            // Set process state
            setProcessState(processId, MuProcessState.SUCCESSFUL, result);
        }
    }

    /* package private */ void cleanupAfterSuccessfulCompensation(
            final int processId
    ) throws MuProcessException {
        setProcessState(processId, MuProcessState.COMPENSATED);
    }

    /* package private */ void cleanupAfterFailedCompensation(
            final int processId
    ) throws MuProcessException {
        setProcessState(processId, MuProcessState.COMPENSATION_FAILED);
    }

    /* package private */ void cleanupAfterFailure(
            final int processId
    ) throws MuProcessException {
        setProcessState(processId, MuProcessState.ABANDONED);
    }

    /* package private */ void dumpStatistics(WorkQueue workQueue) {
        // Prepare collecting statistics for each state
        final int numStates = MuProcessState.values().length;
        long[] stateCount = new long[numStates];
        for (int i = 0; i < numStates; i++) {
            stateCount[i] = 0L;
        }

        //
        try (Connection conn = dataSource.getConnection()) {
            try (PreparedStatement stmt = conn.prepareStatement(
                    getStatement("COUNT_PROCESSES"),
                    ResultSet.TYPE_SCROLL_INSENSITIVE, ResultSet.CONCUR_READ_ONLY)
            ) {
                try (ResultSet rs = Database.executeQuery(stmt)) {
                    while (rs.next()) {
                        // count, state
                        int count = rs.getInt(1);
                        int state = rs.getInt(2);

                        // Assemble some statistics
                        if (state >= 0 && state < numStates) { // as it should be
                            stateCount[state] = count;
                        }
                    }
                }
            }
        }
        catch (SQLException sqle) {
            String info = "Failed to count process headers: ";
            info += Database.squeeze(sqle);
            log.warn(info, sqle);
        }
        catch (MuProcessException mpe) {
            String info = "Cannot dump statistics: ";
            info += mpe.getMessage();
            log.warn(info);
        }

        // Do some reporting
        boolean haveSomethingToDisplay = false;
        int severity = 0;

        StringBuilder statistics = new StringBuilder();
        long total = 0L;
        for (int i = 0; i < numStates; i++) {
            long count = stateCount[i];
            total += count;

            MuProcessState state = MuProcessState.fromInt(i);
            if (count > 0) {
                statistics.append("{").append(count).append(" ").append(state).append("} ");
                haveSomethingToDisplay = true;

                severity = Math.max(severity, i);
            }
        }
        statistics.append("{").append(total).append(" in total} ");
        statistics.append("{").append(workQueue.size()).append(" in queue} ");

        if (haveSomethingToDisplay) {
            if (severity < MuProcessState.COMPENSATION_FAILED.ordinal()) {
                statisticsLog.debug(statistics.toString());
            }
            else if (severity < MuProcessState.ABANDONED.ordinal()) {
                statisticsLog.info(statistics.toString());
            }
            else {
                statisticsLog.warn(statistics.toString());
            }
        }
    }

    /* package private */ void recover(
            final CleanupRunnable runnable
    ) throws MuProcessException {
        try (Connection conn = dataSource.getConnection()) {
            try (PreparedStatement stmt = conn.prepareStatement(
                    getStatement("FETCH_PROCESSES"),
                    ResultSet.TYPE_SCROLL_INSENSITIVE, ResultSet.CONCUR_READ_ONLY)
            ) {
                try (ResultSet rs = Database.executeQuery(stmt)) {
                    while (rs.next()) {
                        // correlation_id, process_id, state, created, modified
                        int idx = 0;
                        String correlationId = rs.getString(++idx);
                        int processId = rs.getInt(++idx);
                        int state = rs.getInt(++idx);
                        Timestamp created = rs.getTimestamp(++idx);
                        Timestamp modified = rs.getTimestamp(++idx);

                        runnable.run(correlationId, processId, state, created, modified);
                    }
                }
            }
        }
        catch (SQLException sqle) {
            String info = "Failed to query process headers: ";
            info += Database.squeeze(sqle);
            log.warn(info, sqle);
        }
    }

    /* package private */ void abandon(String correlationId, int processId) throws MuProcessException {
        log.trace("Abandoning process: correlationId=\"{}\", processId={}", correlationId, processId);
        setProcessState(processId, MuProcessState.ABANDONED);
    }

    /* package private */ void remove(String correlationId, int processId) throws MuProcessException {
        log.trace("Removing process: correlationId=\"{}\", processId={}", correlationId, processId);

        Connection conn = null;
        try {
            conn = dataSource.getConnection();
            conn.setAutoCommit(false);

            try (PreparedStatement stmt = conn.prepareStatement(getStatement("REMOVE_PROCESS_STEPS"))) {
                stmt.setInt(1, processId);
                Database.executeUpdate(stmt);
            }

            try (PreparedStatement stmt = conn.prepareStatement(getStatement("REMOVE_PROCESS"))) {
                stmt.setInt(1, processId);
                Database.executeUpdate(stmt);
            }

            conn.commit();

        }
        catch (SQLException sqle) {
            try {
                if (null != conn) conn.rollback();
            }
            catch (SQLException ignore) {}

            String info = "Failed to remove process: correlationId=\"" + correlationId + "\", processId=" + processId + ": ";
            info += Database.squeeze(sqle);
            log.warn(info, sqle);
            throw new MuProcessException(info, sqle);
        }
        finally {
            try {
                if (null != conn) conn.close();
            }
            catch (SQLException ignore) {}
        }
    }

    ///////////////////////////////////////////////////////////////////////////////////////////////////////////////
    //    Methods called from MuProcess and tightly integrated with the MuProcess lifecycle
    ///////////////////////////////////////////////////////////////////////////////////////////////////////////////

    /* package private */ void touchProcess(
            final MuProcess process
    ) throws MuProcessException {

        // Persist
        if (0 == process.incrementCurrentStep()) {
            // Log process header
            pushProcess(process);
        }

        try (Connection conn = dataSource.getConnection()) {
            // Potentially check whether process state is NEW or (already) PROGRESSING
            try (PreparedStatement stmt = conn.prepareStatement(getStatement("UPDATE_PROCESS"))) {
                int idx = 0;
                stmt.setInt(++idx, MuProcessState.PROGRESSING.toInt());
                stmt.setNull(++idx, Types.CLOB);
                stmt.setInt(++idx, process.getProcessId());
                Database.executeUpdate(stmt);
            }
        }
        catch (SQLException sqle) {
            String info = "Failed to touch process header: ";
            info += Database.squeeze(sqle);
            log.warn(info, sqle);
            throw new MuProcessException(info, sqle);
        }
    }

    /* package private */ void pushCompensation(
            final MuProcess process, final MuBackwardBehaviour activity,
            final MuActivityParameters activityParameters,
            final MuOrchestrationParameters orchestrationParameters,
            final MuActivityState preState
    ) throws MuProcessException {

        // Determine class name
        Class<? extends MuBackwardBehaviour> clazz = activity.getClass();
        String className = clazz.getName();

        // Check existence of persistable method name
        String methodName = activity.getPersistableMethodName();

        try {
            if (false) {
                //---------------------------------------------------------------------------
                // Used during development to trap inadvertent changes to signature of
                // MuBackwardBehaviour#backward, since we have a non-compile time detectable
                // dependency below. This if-statement is never meant to be run but is
                // harmless since constantly false conditional blocks are removed at
                // compile time (as per the Java specification)
                //---------------------------------------------------------------------------
                MuBackwardBehaviour trapChangesToInterface = new MuBackwardBehaviour() {
                    @Override
                    public boolean backward(MuBackwardActivityContext context) {
                        return false;
                    }
                };
            }
            clazz.getMethod(activity.getPersistableMethodName(), MuBackwardActivityContext.class);
        }
        catch (NoSuchMethodException nsme) {
            // Not ever expected to happen in production! Can potentially happen in development though,
            // so it is relevant to prominently log this
            String info = "Failed to validate backward activity: ";
            info += "class=\"" + className + "\", method=\"" + methodName + "\": ";
            info += nsme.getMessage();
            log.warn(info, nsme);
            throw new MuProcessException(info);
        }

        // Persist
        if (0 == process.incrementCurrentStep()) {
            // Log process header
            pushProcess(process);
        }

        if (log.isTraceEnabled()) {
            String info = "Persisting process step " + process.getProcessId() + "#";
            info += process.getCurrentStep();
            info += " class=\"" + className + "\"";
            info += " method=\"" + methodName + "\"";
            log.trace(info);
        }

        Connection conn = null;
        try {
            conn = dataSource.getConnection();
            conn.setAutoCommit(false);

            try (PreparedStatement stmt = conn.prepareStatement(getStatement("STORE_PROCESS_STEP"))) {
                int idx = 0;
                stmt.setInt(++idx, process.getProcessId());
                stmt.setInt(++idx, process.getCurrentStep());

                // class::method of compensation
                stmt.setString(++idx, className);
                stmt.setString(++idx, methodName);

                // activity parameters
                stmt.setCharacterStream(++idx, activityParameters.toReader());

                // orchestration parameters (if applicable)
                if (null != orchestrationParameters && !orchestrationParameters.isEmpty()) {
                    stmt.setCharacterStream(++idx, orchestrationParameters.toReader());
                }
                else {
                    stmt.setNull(++idx, Types.CLOB);
                }

                // pre-state (if applicable)
                if (null != preState && !preState.isEmpty()) {
                    stmt.setCharacterStream(++idx, preState.toReader());
                }
                else {
                    stmt.setNull(++idx, Types.CLOB);
                }
                Database.executeUpdate(stmt);
            }

            // Potentially check whether process state is NEW or (already) PROGRESSING
            try (PreparedStatement stmt = conn.prepareStatement(getStatement("UPDATE_PROCESS"))) {
                int idx = 0;
                stmt.setInt(++idx, MuProcessState.PROGRESSING.toInt());
                stmt.setNull(++idx, Types.CLOB);
                stmt.setInt(++idx, process.getProcessId());
                Database.executeUpdate(stmt);
            }

            conn.commit();
        }
        catch (SQLException sqle) {
            try {
                if (null != conn) conn.rollback();
            }
            catch (SQLException ignore) {}

            String info = "Failed to persist process step: ";
            info += Database.squeeze(sqle);
            log.warn(info, sqle);
            throw new MuProcessException(info, sqle);
        }
        finally {
            try {
                if (null != conn) conn.close();
            }
            catch (SQLException ignore) {}
        }
    }

    /* package private */ void pushCompensation(
            final MuProcess process, final MuBackwardBehaviour activity,
            final MuActivityParameters activityParameters,
            final MuOrchestrationParameters orchestrationParameters
    ) throws MuProcessException {
        pushCompensation(process, activity, activityParameters, orchestrationParameters, null);
    }

    /* package private */ void pushCompensation(
            final MuProcess process, final MuBackwardBehaviour activity,
            final MuActivityParameters activityParameters
    ) throws MuProcessException {
        pushCompensation(process, activity, activityParameters, null, null);
    }

    private void popCompensation(
            Connection conn, final int processId, final int stepId
    ) throws MuProcessException {

        try (PreparedStatement stmt = conn.prepareStatement(getStatement("REMOVE_PROCESS_STEP"))) {
            int idx = 0;
            stmt.setInt(++idx, processId);
            stmt.setInt(++idx, stepId);
            Database.executeUpdate(stmt);
        }
        catch (SQLException sqle) {
            String info = "Failed to remove process step: ";
            info += Database.squeeze(sqle);
            log.warn(info, sqle);
            throw new MuProcessBackwardBehaviourException(info, sqle);
        }
    }
}
