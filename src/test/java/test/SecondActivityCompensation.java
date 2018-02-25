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

import org.gautelis.muprocessmanager.MuActivityParameters;
import org.gautelis.muprocessmanager.MuBackwardBehaviour;

public class SecondActivityCompensation implements MuBackwardBehaviour {

    private static final double backwardExceptionProbability = 0.0001;
    private static final double backwardFailureProbability = 0.05;

    public SecondActivityCompensation() {}

    @Override
    public boolean backward(MuActivityParameters args) {
        // A possibility for an exception
        if (Math.random() < backwardExceptionProbability) {
            throw new NullPointerException("just an example of a nasty failure"); // utter failure
        }

        return !(Math.random() < backwardFailureProbability);
    }
}
