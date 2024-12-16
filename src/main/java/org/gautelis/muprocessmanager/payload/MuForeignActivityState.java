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

import org.gautelis.muprocessmanager.MuActivityState;
import org.gautelis.muprocessmanager.MuBackwardBehaviour;
import org.gautelis.muprocessmanager.MuForwardBehaviour;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.Reader;
import java.io.Serializable;
import java.io.StringReader;
import java.util.Objects;

/**
 * Wraps previous state (whatever that may be) before acted upon by an {@link MuForwardBehaviour activity},
 * and possibly reinstated by a {@link MuBackwardBehaviour compensation}.
 */
public class MuForeignActivityState implements MuActivityState, Serializable {
    private static final long serialVersionUID = 1L;
    private static final Logger log = LoggerFactory.getLogger(MuForeignActivityState.class);

    private String json = null;

    public MuForeignActivityState() {
        json = "";
    }

    public MuForeignActivityState(Reader reader) {
        Objects.requireNonNull(reader, "reader");

        try {
            json = org.apache.commons.io.IOUtils.toString(reader);
        }
        catch (IOException ioe) {
            // Highly unexpected
            String info = "Could not wrap JSON read from database: ";
            info += ioe.getMessage();
            log.warn(info, ioe);
        }
    }

    @Override
    public boolean isEmpty() {
        return null == json || json.isEmpty();
    }

    /**
     * Creates a MuActivityState from a JSON stream.
     * @param reader JSON stream
     * @return MuActivityParameters made from JSON stream
     */
    public static MuForeignActivityState fromReader(final Reader reader) {
        return new MuForeignActivityState(reader);
    }

    /**
     * Creates a JSON stream from a MuActivityState
     * @return Reader a JSON stream made from this object
     */
    @Override
    public Reader toReader() {
        return new StringReader(toJson());
    }

    @Override
    public String toJson() {
        return json;
    }

    @Override
    public String toString() {
        return getClass().getName() + "(\"" + json + "\")";
    }
}
