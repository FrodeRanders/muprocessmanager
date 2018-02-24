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

import eu.ensure.muprocessmanager.utils.Cloner;

import java.io.IOException;

/**
 * Corresponds to an individual volatile process step.
 */
/* package private */ class MuVolatileProcessStep {

    private final MuActivityParameters parameters;

    private MuBackwardBehaviour backwardBehaviour = null;

    /* package private */ MuVolatileProcessStep(final MuActivityParameters parameters) throws MuProcessException {
        try {
            this.parameters = Cloner.clone(parameters);
        }
        catch (IOException | ClassNotFoundException e) {
            String info = "Could not clone activity parameters for process step: ";
            info += e.getMessage();
            throw new MuProcessException(info, e);
        }
    }

    /* package private */ boolean execute(final MuForwardBehaviour forward, final MuBackwardBehaviour backward) {
        backwardBehaviour = backward;

        // Run forward transaction
        boolean success;
        try {
            success = forward.forward(parameters);
        }
        catch (Throwable t) {
            success = false;
        }

        return success;
    }

    /* package private */ boolean compensate() {
        // Run backward transaction
        boolean success;
        try {
            success = backwardBehaviour.backward(parameters);
        }
        catch (Throwable t) {
            success = false;
        }

        return success;
    }
}
