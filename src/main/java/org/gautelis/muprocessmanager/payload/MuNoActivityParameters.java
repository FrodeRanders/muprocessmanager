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
package org.gautelis.muprocessmanager.payload;

import org.gautelis.muprocessmanager.MuActivity;
import org.gautelis.muprocessmanager.MuActivityParameters;
import org.gautelis.muprocessmanager.MuOrchestrationParameters;

import java.io.Reader;
import java.io.Serializable;
import java.io.StringReader;
import java.util.Optional;

/**
 * Wraps empty parameters to a {@link MuActivity}.
 */
public class MuNoActivityParameters implements MuActivityParameters, Serializable {
    private static final long serialVersionUID = 1L;

    /**
     * Creates a JSON stream from a MuActivityParameters
     *
     * @return Reader a JSON stream made from this object
     */
    @Override
    public Reader toReader() {
        return new StringReader("");
    }

    /**
     * Retrns internal representation as JSON
     * @return JSON representation
     */
    @Override
    public String toJson() {
        return "";
    }

    public void putOrchestrationParameter(String key, String value) {
    }

    public Optional<String> getOrchestrationParameter(String key) {
        return Optional.empty();
    }

    public MuOrchestrationParameters getOrchestrationParameters() {
        return new MuOrchestrationParameters();
    }

    public void putOrchestrationParameter(MuOrchestrationParameters parameters) {
    }
}