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
import java.util.Optional;

public class MuBackwardActivityContext {

    private final String correlationId;
    private final boolean acceptCompensationFailure;
    private final MuActivityParameters activityParameters;
    private final MuOrchestrationParameters orchestrationParameters;
    private final MuActivityState preState;

    /* package private */
    MuBackwardActivityContext(
            String correlationId, boolean acceptCompensationFailure, MuActivityParameters activityParameters,
            MuOrchestrationParameters orchestrationParameters, MuActivityState preState
    ) {
        Objects.requireNonNull(correlationId, "correlationId");
        Objects.requireNonNull(activityParameters, "activityParameters");

        this.correlationId = correlationId;
        this.acceptCompensationFailure = acceptCompensationFailure;
        this.activityParameters = activityParameters;
        this.orchestrationParameters = orchestrationParameters;
        this.preState = preState;
    }

    public boolean usesNativeDataFlow() {
        return activityParameters.isNative();
    }

    public String getCorrelationId() {
        return correlationId;
    }

    public boolean acceptCompensationFailure() {
        return acceptCompensationFailure;
    }

    public MuActivityParameters getActivityParameters() {
        return activityParameters;
    }

    public Optional<MuOrchestrationParameters> getOrchestrationParameters() {
        return Optional.ofNullable(orchestrationParameters);
    }

    public Optional<MuActivityState> getPreState() {
        return Optional.ofNullable(preState);
    }
}
