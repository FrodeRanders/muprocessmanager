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

public class MuForwardActivityContext {

    private final String correlationId;
    private final MuActivityParameters activityParameters;
    private final MuProcessResult result;

    /* package private */
    MuForwardActivityContext(
            String correlationId, MuActivityParameters activityParameters, MuProcessResult result
    ) {
        Objects.requireNonNull(correlationId, "correlationId");
        Objects.requireNonNull(activityParameters, "activityParameters");
        Objects.requireNonNull(result, "result");

        this.correlationId = correlationId;
        this.activityParameters = activityParameters;
        this.result = result;
    }

    public boolean usesNativeDataFlow() {
        return activityParameters.isNative() && result.isNative();
    }

    public String getCorrelationId() {
        return correlationId;
    }

    public MuProcessResult getResult() {
        return result;
    }

    public MuActivityParameters getActivityParameters() {
        return activityParameters;
    }
}
