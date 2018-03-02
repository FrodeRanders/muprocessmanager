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
package test;

import org.gautelis.muprocessmanager.MuActivity;
import org.gautelis.muprocessmanager.MuActivityParameters;
import org.gautelis.muprocessmanager.MuProcessResult;
import org.gautelis.muprocessmanager.MuProcessState;

import java.util.Optional;

public class ThirdActivity implements MuActivity {

    private static final double forwardFailureProbability = 0.10;
    private static final double backwardFailureProbability = 0.01;

    public ThirdActivity() {}

    @Override
    public boolean forward(MuActivityParameters args, MuProcessResult results) {
        boolean cutInHalf = (boolean)args.get("shrink-head");
        if (cutInHalf) {
            double stepTwoResult = (double) results.remove(0);
            results.add(stepTwoResult / 2.0);
        }
        return !(Math.random() < forwardFailureProbability);
    }

    @Override
    public boolean backward(MuActivityParameters args, Optional<MuProcessState> preState) {
        return !(Math.random() < backwardFailureProbability);
    }
}
