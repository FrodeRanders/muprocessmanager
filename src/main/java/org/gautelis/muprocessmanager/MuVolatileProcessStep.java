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

import java.util.Objects;

/**
 * Corresponds to an individual volatile process step.
 */
/* package private */
class MuVolatileProcessStep {

    private final String correlationId;
    private final MuActivityParameters activityParameters;

    private MuBackwardBehaviour backwardBehaviour = null;

    /* package private */
    MuVolatileProcessStep(String correlationId, final MuActivityParameters activityParameters) {
        Objects.requireNonNull(correlationId, "correlationId");
        Objects.requireNonNull(activityParameters, "activityParameters");

        this.correlationId = correlationId;
        this.activityParameters = activityParameters;
    }

    /* package private */
    boolean execute(
            final MuForwardBehaviour forward, final MuBackwardBehaviour backward,
            final MuProcessResult result
    ) {
        Objects.requireNonNull(forward, "forward");
        Objects.requireNonNull(backward, "backward");
        Objects.requireNonNull(result, "result");

        backwardBehaviour = backward;

        // Run forward transaction
        boolean success;
        try {
            MuForwardActivityContext context = new MuForwardActivityContext(correlationId, activityParameters, result);
            success = forward.forward(context);
        }
        catch (Throwable t) {
            success = false;
        }

        return success;
    }

    /* package private */
    boolean compensate() {
        // Run backward transaction
        boolean success;
        try {
            boolean acceptCompensationFailure = /* does not matter either way */ true;
            MuBackwardActivityContext context = new MuBackwardActivityContext(correlationId, acceptCompensationFailure, activityParameters, null, null);
            success = backwardBehaviour.backward(context);
        }
        catch (Throwable t) {
            success = false;
        }

        return success;
    }
}
