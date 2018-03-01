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

import org.gautelis.vopn.io.Cloner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.Optional;

/**
 * Models a micro-process, identified by a unique correlation ID, in which individual
 * activities may be executed. As such, this micro-process keeps track of activities
 * as they occur and there is no support for anything fancy like templating a process.
 * Use a process engine in that case -- but that may not be particularly _micro_.
 */
public class MuProcess {
    private static final Logger log = LoggerFactory.getLogger(MuProcess.class);

    private final static String LAMBDA_INDICATION = "lambda$";

    //
    private final String correlationId;
    private int processId = 0; // meaning as yet unknown

    //
    private int currentStep = 0; // meaning no steps yet
    private final MuPersistentLog compensationLog;

    //
    private final boolean acceptCompensationFailure;

    //
    final MuProcessResult result = new MuProcessResult();

    /* package private */ MuProcess(
            final String correlationId, MuPersistentLog compensationLog, final boolean acceptCompensationFailure
    ) {
        this.correlationId = correlationId;
        this.compensationLog = compensationLog;
        this.acceptCompensationFailure = acceptCompensationFailure;
    }

    public boolean getAcceptCompensationFailure() {
        return acceptCompensationFailure;
    }

    /* package private */ int getProcessId() {
        return processId;
    }

    /* package private */ void setProcessId(int processId) {
        this.processId = processId;
    }

    public String getCorrelationId() {
        return correlationId;
    }

    /* package private */ int incrementCurrentStep() {
        return currentStep++; // returning previous
    }

    public int getCurrentStep() {
        return currentStep;
    }

    /**
     * Get results associated with this process.
     * @return process result(s) so far.
     */
    public MuProcessResult getResult() {
        return result;
    }

    public void execute(
            final MuActivity activity,
            final MuActivityParameters parameters
    ) throws MuProcessException {

        MuActivityParameters parametersSnapshot;
        try {
            parametersSnapshot = Cloner.clone(parameters);
        }
        catch (IOException | ClassNotFoundException e) {
            String info = this + ": Failed to make snapshot of activity parameters for " + activity.getClass().getName();
            throw new MuProcessException(info, e);
        }

        // Log backward activity
        compensationLog.pushCompensation(this, activity, parametersSnapshot);

        // Run forward action
        boolean forwardSuccess;
        try {
            forwardSuccess = activity.forward(parameters, result);
        }
        catch (Throwable t) {
            String info = this + ": Forward activity (\"" + activity.getClass().getName() + "\") step " + currentStep + " failed: ";
            info += t.getMessage();
            log.info(info, t);

            forwardSuccess = false;
        }

        if (!forwardSuccess) {
            // So we failed. Now run backward actions, and throw exception corresponding to
            // relevant syndrome:
            //     - failed, but managed to compensate
            //     - failed and so did compensation(s)
            throw compensate(compensationLog, correlationId, processId, acceptCompensationFailure);
        }
    }

    public void execute(
            final MuForwardBehaviour forwardBehaviour, final MuBackwardBehaviour backwardBehaviour,
            final MuActivityParameters parameters
    ) throws MuProcessException {

        String backwardClassName = backwardBehaviour.getClass().getName();
        if (backwardClassName.contains(LAMBDA_INDICATION)) {
            String info = "Backward behaviour can not be a lambda: " + backwardClassName;
            throw new MuProcessException(info);
        }

        MuActivityParameters parametersSnapshot;
        try {
            parametersSnapshot = Cloner.clone(parameters);
        }
        catch (IOException | ClassNotFoundException e) {
            String info = this + ": Failed to make snapshot of activity parameters for " + backwardClassName;
            throw new MuProcessException(info, e);
        }

        // Log backward activity
        compensationLog.pushCompensation(this, backwardBehaviour, parametersSnapshot);

        // Run forward action
        boolean forwardSuccess;
        try {
            forwardSuccess = forwardBehaviour.forward(parameters, result);
        }
        catch (Throwable t) {
            String info = this + ": Forward activity (\"" + forwardBehaviour.getClass().getName() + "\") step " + currentStep + " failed: ";
            info += t.getMessage();
            log.info(info, t);

            forwardSuccess = false;
        }

        if (!forwardSuccess) {
            // So we failed. Now run backward actions, and throw exception corresponding to
            // relevant syndrome:
            //     - failed, but managed to compensate
            //     - failed and so did compensation(s)
            throw compensate(compensationLog, correlationId, processId, acceptCompensationFailure);
        }
    }

    /**
     * The process has finished successfully with the current accumulated process
     * {@link MuProcessResult result}. The result will be retained (for a while) and
     * may be retrieved later.
     *
     * The scenario that we had in mind was a timeout somewhere between the caller and
     * the micro process implementation, where the micro process finishes successfully
     * but the caller gets a timeout and may not now whether the process succeeded.
     *
     * The process may be queried for it's state and the result retrieved if the process was
     * {@link MuProcessStatus#SUCCESSFUL SUCCESSFUL}.
     */
    public void finished() {
        try {
            compensationLog.cleanupAfterSuccess(getProcessId(), result);
        }
        catch (Exception mpe) {
            String info = "Failed to mark process as successful: ";
            info += mpe.getMessage();
            log.warn(info);
        }
    }

    /**
     * Informs the process manager (indirectly) that your process failed. The nature
     * of this failure is fully up to the process to decide for itself.
     */
    public void failed() {
        try {
            compensationLog.cleanupAfterFailure(getProcessId());
        }
        catch (MuProcessException mpe) {
            String info = "Failed to mark process as failed: ";
            info += mpe.getMessage();
            log.warn(info);
        }
    }

    /* package private */ Optional<MuProcessStatus> getProcessStatus() throws MuProcessException {
        return compensationLog.getProcessStatus(correlationId);
    }

    @Override
    public String toString() {
        return "Process[" + "correlationId=\"" + correlationId + "\", " + "processId=\"" + processId + "\"" + "]";
    }

    ///////////////////////////////////////////////////////////////////////////////////////////////////////////////

    private static class FailedCompensation {
        private int step;
        private String activityName;

        FailedCompensation(int step, String activityName) {
            this.step = step;
            this.activityName = activityName;
        }

        private int getStep() {
            return step;
        }

        private String getActivityName() {
            return activityName;
        }
    }

    /* package private */ static MuProcessException compensate(
            final MuPersistentLog compensationLog,
            final String correlationId, final int processId,
            final boolean acceptCompensationFailure
    ) throws MuProcessException {

        MuProcessException exception;

        List<FailedCompensation> failedCompensations = new LinkedList<>();
        try {
            compensationLog.compensate(processId, (activity, method, parameters, step, retries) -> {
                boolean compensationSuccess;

                String activityName = activity.getClass().getName();

                try {
                    // Run backward transaction
                    compensationSuccess = (boolean) method.invoke(activity, parameters);

                    // Record failure, if needed
                    if (!compensationSuccess) {
                        failedCompensations.add(new FailedCompensation(step, activityName));
                    }
                } catch (Throwable t) {
                    // Record failure
                    compensationSuccess = false;
                    failedCompensations.add(new FailedCompensation(step, activityName));

                    if (!acceptCompensationFailure) {
                        // Handling when having a throwable, i.e. compensation failed catastrophically
                        String info = "Failed to compensate step " + step + " activity (\"" + activityName + "\"): correlationId=\"" + correlationId + "\"";
                        throw new MuProcessBackwardBehaviourException(info, t);
                    }
                }

                if (!compensationSuccess) {
                    if (log.isTraceEnabled()) {
                        String info = "Failed to compensate step " + step + " activity (\"" + activityName + "\"): correlationId=\"" + correlationId + "\" [continuing]";
                        log.trace(info);
                    }
                    
                    if (!acceptCompensationFailure) {
                        // Handling without throwable, i.e. compensation failed in a controlled manner.
                        String info = "Failed to compensate step " + step + " activity (\"" + activityName + "\"): correlationId=\"" + correlationId + "\"";
                        throw new MuProcessBackwardBehaviourException(info);
                    }
                }

                return compensationSuccess;
            });
        }
        finally {
            if (failedCompensations.size() == 0) {
                compensationLog.cleanupAfterSuccessfulCompensation(processId);

                String info = "Forward activity failed, but compensations were successful";
                exception = new MuProcessForwardBehaviourException(info);

            } else {
                compensationLog.cleanupAfterFailedCompensation(processId);

                StringBuilder info = new StringBuilder("Forward activity failed and so did some compensation activities: ");
                for (FailedCompensation failedCompensation : failedCompensations) {
                    info.append("{step=").append(failedCompensation.getStep());
                    info.append(" activity=").append(failedCompensation.getActivityName()).append("} ");
                }
                exception = new MuProcessBackwardBehaviourException(info.toString());
            }
        }
        return exception;
    }
}
