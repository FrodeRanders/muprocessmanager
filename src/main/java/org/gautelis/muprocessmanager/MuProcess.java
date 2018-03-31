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

import org.gautelis.muprocessmanager.payload.MuForeignProcessResult;
import org.gautelis.muprocessmanager.payload.MuNativeProcessResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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

    public final static int PROCESS_ID_NOT_YET_ASSIGNED = -1;
    private final static String LAMBDA_INDICATION = "lambda$";

    //
    private final String correlationId;
    private int processId = PROCESS_ID_NOT_YET_ASSIGNED;

    //
    private int currentStep = 0; // meaning no steps yet
    private final MuPersistentLog compensationLog;

    //
    private final boolean acceptCompensationFailure;

    //
    final MuProcessResult result;

    /* package private */ MuProcess(
            final String correlationId, MuPersistentLog compensationLog,
            final boolean acceptCompensationFailure, final boolean assumeNativeProcessDataFlow
    ) {
        this.correlationId = correlationId;
        this.compensationLog = compensationLog;
        this.acceptCompensationFailure = acceptCompensationFailure;

        if (assumeNativeProcessDataFlow) {
            result = new MuNativeProcessResult();
        }
        else {
            result = new MuForeignProcessResult();
        }
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

    /**
     * Executes an activity that only has a {@link MuForwardBehaviour forward behaviour} and no
     * {@link MuBackwardBehaviour backward behaviour}. As such, we are <strong>NOT</strong> utilizing any of the
     * behaviour laid forth in
     * <a href="https://pdfs.semanticscholar.org/1155/490b99d6a2501f7bf79e4456a5c6c2bc153a.pdf">this article</a>
     * about Sagas.
     * <p>
     * Appropriate if a micro process mixes activities both with and without the need for compensation.
     * Even in this case, the mechanisms around {@link MuActivityState process state} works, so that it is possible
     * to check state of processes and claim process results (for a configurable period of time).
     * <p>
     * This version of execute does not honour any {@link MuActivityState process state} -- since there
     * can be no compensation for this activity.
     * @param forwardBehaviour the forward behaviour of the activity to execute -- may be a lambda
     * @param activityParameters parameters to the 'forward' as well as the 'backward' behaviour of the activity.
     * @throws MuProcessForwardBehaviourException if forward behaviour failed, but all compensations were successful
     * @throws MuProcessBackwardBehaviourException if forward behaviour failed and also at least some compensation behaviour
     */
    public void execute(
            final MuForwardBehaviour forwardBehaviour,
            final MuActivityParameters activityParameters
    ) throws MuProcessException {

        // Run forward action
        boolean forwardSuccess;
        try {
            compensationLog.touchProcess(this);

            MuForwardActivityContext context = new MuForwardActivityContext(correlationId, activityParameters, result);
            forwardSuccess = forwardBehaviour.forward(context);
        }
        catch (Throwable t) {
            String info = this + ": Forward activity (\"" + forwardBehaviour.getClass().getName() + "\") step " + currentStep + " failed: ";
            info += t.getMessage();
            log.info(info, t);

            forwardSuccess = false;
        }

        if (!forwardSuccess) {
            // So we failed. Throw exception corresponding to
            // relevant syndrome:
            //     - failed, but managed to compensate
            //     - failed and so did compensation(s)
            throw compensate(compensationLog, correlationId, processId);
        }
    }


    /**
     * Executes an {@link MuActivity activity} in a {@link MuProcess process},
     * using the behaviour laid forth in <a href="https://pdfs.semanticscholar.org/1155/490b99d6a2501f7bf79e4456a5c6c2bc153a.pdf">this article</a> about Sagas.
     * @param activity the activity to execute
     * @param activityParameters business parameters to the 'forward' as well as the 'backward' behaviour of the activity.
     * @param orchestrationParameters orchestration parameters to the 'backward' behaviour of the activity.
     * @throws MuProcessForwardBehaviourException if forward behaviour failed, but all compensations were successful
     * @throws MuProcessBackwardBehaviourException if forward behaviour failed and also at least some compensation behaviour
     */
    public void execute(
            final MuActivity activity,
            final MuActivityParameters activityParameters,
            final MuOrchestrationParameters orchestrationParameters
    ) throws MuProcessException {

        final Optional<MuActivityState> preState = activity.getState();

        // Run forward action
        boolean forwardSuccess;
        try {
            // Log backward activity
            if (preState.isPresent()) {
                compensationLog.pushCompensation(this, activity, activityParameters, orchestrationParameters, preState.get());
            }
            else {
                compensationLog.pushCompensation(this, activity, activityParameters, orchestrationParameters);
            }
            MuForwardActivityContext context = new MuForwardActivityContext(correlationId, activityParameters, result);
            forwardSuccess = activity.forward(context);
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
            throw compensate(compensationLog, correlationId, processId);
        }
    }

    /**
     * Executes an {@link MuActivity activity} in a {@link MuProcess process},
     * using the behaviour laid forth in <a href="https://pdfs.semanticscholar.org/1155/490b99d6a2501f7bf79e4456a5c6c2bc153a.pdf">this article</a> about Sagas.
     * @param activity the activity to execute
     * @param activityParameters business parameters to the 'forward' as well as the 'backward' behaviour of the activity.
     * @throws MuProcessForwardBehaviourException if forward behaviour failed, but all compensations were successful
     * @throws MuProcessBackwardBehaviourException if forward behaviour failed and also at least some compensation behaviour
     */
    public void execute(
            final MuActivity activity,
            final MuActivityParameters activityParameters
    ) throws MuProcessException {
        execute(activity, activityParameters, null);
    }

    /**
     * Executes an activity, by means of the two constituents {@link MuForwardBehaviour forward behaviour}
     * and {@link MuBackwardBehaviour backward behaviour}, using the behaviour laid forth in
     * <a href="https://pdfs.semanticscholar.org/1155/490b99d6a2501f7bf79e4456a5c6c2bc153a.pdf">this article</a>
     * about Sagas.
     * @param forwardBehaviour the forward behaviour of the activity to execute -- may be a lambda
     * @param backwardBehaviour the backward behaviour of the activity to execute -- may <strong>NOT</strong> be a lambda since we need to know what class to instantiate object from during compensation
     * @param activityParameters parameters to the 'forward' as well as the 'backward' behaviour of the activity.
     * @param orchestrationParameters orchestration parameters to the 'backward' behaviour of the activity.
     * @throws MuProcessForwardBehaviourException if forward behaviour failed, but all compensations were successful
     * @throws MuProcessBackwardBehaviourException if forward behaviour failed and also at least some compensation behaviour
     */
    public void execute(
            final MuForwardBehaviour forwardBehaviour, final MuBackwardBehaviour backwardBehaviour,
            final MuActivityParameters activityParameters, final MuOrchestrationParameters orchestrationParameters
    ) throws MuProcessException {

        String backwardClassName = backwardBehaviour.getClass().getName();
        if (backwardClassName.contains(LAMBDA_INDICATION)) {
            String info = "Backward behaviour can not be a lambda: " + backwardClassName;
            throw new MuProcessException(info);
        }

        final Optional<MuActivityState> preState = forwardBehaviour.getState();

        // Run forward action
        boolean forwardSuccess;
        try {
            // Log backward activity
            if (preState.isPresent()) {
                compensationLog.pushCompensation(this, backwardBehaviour, activityParameters, orchestrationParameters, preState.get());
            }
            else {
                compensationLog.pushCompensation(this, backwardBehaviour, activityParameters, orchestrationParameters);
            }

            MuForwardActivityContext context = new MuForwardActivityContext(correlationId, activityParameters, result);
            forwardSuccess = forwardBehaviour.forward(context);
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
            throw compensate(compensationLog, correlationId, processId);
        }
    }

    /**
     * Executes an activity, by means of the two constituents {@link MuForwardBehaviour forward behaviour}
     * and {@link MuBackwardBehaviour backward behaviour}, using the behaviour laid forth in
     * <a href="https://pdfs.semanticscholar.org/1155/490b99d6a2501f7bf79e4456a5c6c2bc153a.pdf">this article</a>
     * about Sagas.
     * @param forwardBehaviour the forward behaviour of the activity to execute -- may be a lambda
     * @param backwardBehaviour the backward behaviour of the activity to execute -- may <strong>NOT</strong> be a lambda since we need to know what class to instantiate object from during compensation
     * @param activityParameters parameters to the 'forward' as well as the 'backward' behaviour of the activity.
     * @throws MuProcessForwardBehaviourException if forward behaviour failed, but all compensations were successful
     * @throws MuProcessBackwardBehaviourException if forward behaviour failed and also at least some compensation behaviour
     */
    public void execute(
            final MuForwardBehaviour forwardBehaviour, final MuBackwardBehaviour backwardBehaviour,
            final MuActivityParameters activityParameters
    ) throws MuProcessException {
        execute(forwardBehaviour, backwardBehaviour, activityParameters, null);
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
     * {@link MuProcessState#SUCCESSFUL SUCCESSFUL}.
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

    /* package private */ Optional<MuProcessState> getProcessState() throws MuProcessException {
        return compensationLog.getProcessState(correlationId);
    }

    @Override
    public String toString() {
        return "Process[" + "correlationId=\"" + correlationId + "\", " + "processId=" + processId + "]";
    }

    ///////////////////////////////////////////////////////////////////////////////////////////////////////////////

    private static class FailedCompensation {
        private final int step;
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

    /*
     * This is the synchronous handling of compensation, which has another
     * treatment of acceptCompensationFailure than has the asynchronous one.
     */
    /* package private */ static MuProcessException compensate(
            final MuPersistentLog compensationLog,
            final String correlationId, final int processId
    ) throws MuProcessException {

        MuProcessException exception;

        List<FailedCompensation> failedCompensations = new LinkedList<>();
        try {
            compensationLog.compensate(processId, (activity, method, context, step, retries) -> {
                boolean compensationSuccess;

                String activityName = activity.getClass().getName();

                try {
                    // Run backward transaction
                    compensationSuccess = (boolean) method.invoke(activity, context);

                    // Record failure, if needed
                    if (!compensationSuccess) {
                        failedCompensations.add(new FailedCompensation(step, activityName));
                    }
                } catch (Throwable t) {
                    // Record failure
                    compensationSuccess = false;
                    failedCompensations.add(new FailedCompensation(step, activityName));

                    if (!context.acceptCompensationFailure()) {
                        // Handling when having a throwable, i.e. compensation failed catastrophically
                        String info = "Failed to compensate step " + step + " activity (\"" + activityName + "\"): correlationId=\"" + correlationId + "\"";
                        throw new MuProcessBackwardBehaviourException(info, t);
                    }
                }

                if (!compensationSuccess) {
                    log.trace("Failed to compensate step {} activity (\"{}\"): correlationId=\"{}\"", step, activityName, correlationId);

                    if (!context.acceptCompensationFailure()) {
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
