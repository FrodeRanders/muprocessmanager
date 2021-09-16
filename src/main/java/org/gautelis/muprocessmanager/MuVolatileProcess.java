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

import org.gautelis.muprocessmanager.payload.MuForeignProcessResult;
import org.gautelis.muprocessmanager.payload.MuNativeProcessResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.Stack;

/**
 * This is a volatile process, in which you can execute activities. If the process fails,
 * compensations are executed (similar to the {@link MuProcess}) but without the persistence
 * steps. If your process thread dies, the state is lost!
 * <p>
 * Since compensations (i.e. {@link MuBackwardBehaviour})are not backed to database, you may
 * use lambdas both for the forward and backward behaviours of each activity.
 * <p>
 * This is a convenience utility for creating micro-processes that may fail.
 */
public class MuVolatileProcess {
    private static final Logger log = LoggerFactory.getLogger(MuVolatileProcess.class);

    private final String correlationId;
    private final boolean acceptCompensationFailure;

    private final Stack<MuVolatileProcessStep> stepStack = new Stack<>();

    //
    private final MuProcessResult result;


    /* package private */
    MuVolatileProcess(
            final String correlationId, final boolean acceptCompensationFailure, final boolean assumeNativeProcessDataFlow
    ) {
        Objects.requireNonNull(correlationId, "correlationId");

        this.correlationId = correlationId;
        this.acceptCompensationFailure = acceptCompensationFailure;

        if (assumeNativeProcessDataFlow) {
            result = new MuNativeProcessResult();
        }
        else {
            result = new MuForeignProcessResult();
        }
    }

    /**
     * Get results associated with this process.
     * @return process result(s) so far.
     */
    public MuProcessResult getResult() {
        return result;
    }

    public <P> void execute(
            final MuForwardBehaviour forward, final MuBackwardBehaviour backward,
            final MuActivityParameters parameters
    ) throws MuProcessException {
        Objects.requireNonNull(forward, "forward");
        Objects.requireNonNull(backward, "backward");
        Objects.requireNonNull(parameters, "parameters");

        MuVolatileProcessStep step = new MuVolatileProcessStep(correlationId, parameters);
        stepStack.push(step);

        boolean forwardSuccess;
        try {
            forwardSuccess = step.execute(forward, backward, result);
        }
        catch (Throwable t) {
            forwardSuccess = false;
        }

        if (!forwardSuccess) {
            boolean backwardStepSuccess, overallBackwardSuccess = true;

            while (!stepStack.empty()) {
                int stepNumber = stepStack.size();
                MuVolatileProcessStep priorStep = stepStack.pop();

                try {
                    backwardStepSuccess = priorStep.compensate();
                }
                catch (Throwable t) {
                    backwardStepSuccess = false;
                    if (!acceptCompensationFailure) {
                        String info = "Failed to compensate activity " + stepNumber;
                        throw new MuProcessBackwardBehaviourException(info, t);
                    }
                }
                overallBackwardSuccess &= backwardStepSuccess;

                if (!backwardStepSuccess) {
                    String info = "Failed to compensate step " + stepNumber + " [continuing]";
                    log.warn(info);
                }
            }

            if (overallBackwardSuccess) {
                String info = "Forward activity failed, ";
                info += "but compensation was successful.";
                throw new MuProcessForwardBehaviourException(info);
            } else {
                String info = "Forward activity failed ";
                info += "and also failed to compensate all activities";
                throw new MuProcessBackwardBehaviourException(info);
            }
        }
    }
}
