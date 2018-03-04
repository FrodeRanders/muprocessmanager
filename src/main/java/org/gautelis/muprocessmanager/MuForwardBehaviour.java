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
package org.gautelis.muprocessmanager;

import java.util.Optional;

/**
 * A representation of the forward ("happy path") behaviour of a {@link MuActivity}.
 */
@FunctionalInterface
public interface MuForwardBehaviour {
    /**
     * Retrieve pre-state, i.e. state before the invocation of the forward action
     * and possibly the state we want to revert to by means of a compensation.
     * <p>
     * There is a risk, however, that two processes acting on the same subject
     * may interfere with each other so that there is in fact a serialization
     * requirement present. Consider this carefully before using this feature
     * (which is disabled by default)
     * <p>
     * Will be called ahead of call to {@link MuForwardBehaviour#forward forward}
     * to retrieve the 'state' before {@link MuForwardBehaviour#forward forward}
     * was called. Will later be provided to the {@link MuBackwardBehaviour#backward compensation},
     * if relevant.
     * @return previous state if such a state is relevant
     */
    default Optional<MuActivityState> getState() { return Optional.empty(); }

    /**
     * Execute forward behaviour.
     * @param arguments arguments to the forward transaction
     * @param result results of the forward transaction
     * @return true if successful, false otherwise
     */
    boolean forward(MuActivityParameters arguments, final MuProcessResult result);
}
