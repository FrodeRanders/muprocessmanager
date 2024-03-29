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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.util.*;

/**
 * Implements the synchronous functionality of the micro-process manager.
 * <p>
 * Acquire a {@link MuProcess} from this manager and execute your {@link MuActivity}
 * in this process -- this manager will take care of potential failures by automatically
 * running compensations. Compensations are persisted to a relational database (in
 * the background).
 */
public class MuSynchronousManagerImpl implements MuSynchronousManager {
    private static final Logger log = LoggerFactory.getLogger(MuSynchronousManagerImpl.class);

    //
    private final boolean acceptCompensationFailure;
    private final boolean assumeNativeProcessDataFlow;
    private final boolean onlyCompensateIfTransactionWasSuccessful;

    //
    private final MuPersistentLog compensationLog;
    private static final boolean DEBUG = false; // debug database setup?


    /* package private */
    MuSynchronousManagerImpl(DataSource dataSource, Properties sqlStatements, MuProcessManagementPolicy policy) {
        Objects.requireNonNull(dataSource, "dataSource");
        Objects.requireNonNull(sqlStatements, "sqlStatements");
        Objects.requireNonNull(policy, "policy");

        acceptCompensationFailure = policy.acceptCompensationFailure();
        assumeNativeProcessDataFlow = policy.assumeNativeProcessDataFlow();
        onlyCompensateIfTransactionWasSuccessful = policy.onlyCompensateIfTransactionWasSuccessful();

        compensationLog = new MuPersistentLog(dataSource, sqlStatements, assumeNativeProcessDataFlow);
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
     * all processes, use the more specific {@link MuSynchronousManagerImpl#newProcess(String, boolean)} instead.
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
}
