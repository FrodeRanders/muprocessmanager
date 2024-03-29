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
package test;

import org.gautelis.muprocessmanager.MuActivity;
import org.gautelis.muprocessmanager.MuActivityState;
import org.gautelis.muprocessmanager.MuBackwardActivityContext;
import org.gautelis.muprocessmanager.MuForwardActivityContext;
import org.gautelis.muprocessmanager.payload.MuNativeActivityParameters;
import org.gautelis.muprocessmanager.payload.MuNativeActivityState;
import org.gautelis.muprocessmanager.payload.MuNativeProcessResult;

import java.util.Optional;

public class FourthActivity implements MuActivity {

    private static final double forwardFailureProbability = 0.25;
    private static final double backwardExceptionProbability = 0.001;
    private static final double backwardFailureProbability = 0.01;

    public FourthActivity() {}

    @Override
    public boolean forward(MuForwardActivityContext context) {
        if (context.usesNativeDataFlow()) {
            MuNativeActivityParameters parameters = (MuNativeActivityParameters)context.getActivityParameters();
            MuNativeProcessResult result = (MuNativeProcessResult)context.getResult();
            double piApprox = (double) parameters.get("pi-kinda");
            double hatSize = (double) result.remove(0);
            result.add(piApprox * hatSize);
        }
        return !(Math.random() < forwardFailureProbability);
    }

    @Override
    public Optional<MuActivityState> getState() {
        MuNativeActivityState preState = new MuNativeActivityState();
        preState.put("state1", "Fourth activity pre-state");
        preState.put("state2", Math.random());

        return Optional.of(preState);
    }

    @Override
    public boolean backward(MuBackwardActivityContext context) {

        // A possibility for an exception
        if (Math.random() < backwardExceptionProbability) {
            throw new NullPointerException("just an example of a nasty failure"); // utter failure
        }

        //preState.ifPresent(state -> System.out.println("Compensate to state: " + state));
        return !(Math.random() < backwardFailureProbability);
    }
}
