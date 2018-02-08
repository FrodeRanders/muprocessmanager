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

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Stack;

public class MuVolatileProcess {
    private static final Logger log = LogManager.getLogger(MuVolatileProcess.class);

    private final boolean acceptCompensationFailure;

    private Stack<MuVolatileProcessStep> stepStack = new Stack<>();

    /* package private */ MuVolatileProcess(final boolean acceptCompensationFailure) {
        this.acceptCompensationFailure = acceptCompensationFailure;
    }

    public <P> void execute(final MuForwardBehaviour forward, final MuBackwardBehaviour backward, final MuActivityParameters parameters) throws MuProcessException {

        MuVolatileProcessStep step = new MuVolatileProcessStep(parameters);
        stepStack.push(step);

        boolean forwardSuccess;
        try {
            forwardSuccess = step.execute(forward, backward);
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
                        throw new MuProcessBackwardActivityException(info, t);
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
                throw new MuProcessForwardActivityException(info);
            } else {
                String info = "Forward activity failed ";
                info += "and also failed to compensate all activities";
                throw new MuProcessBackwardActivityException(info);
            }
        }
    }
}
