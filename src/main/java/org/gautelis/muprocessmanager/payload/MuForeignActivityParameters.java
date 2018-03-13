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
package org.gautelis.muprocessmanager.payload;

import org.apache.commons.io.IOUtils;
import org.gautelis.muprocessmanager.MuActivity;
import org.gautelis.muprocessmanager.MuActivityParameters;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.Reader;
import java.io.Serializable;
import java.io.StringReader;

/**
 * Wraps foreign parameters to a {@link MuActivity}.
 */
public class MuForeignActivityParameters implements MuActivityParameters, Serializable {
    private static final long serialVersionUID = 1L;
    private static final Logger log = LoggerFactory.getLogger(MuForeignActivityParameters.class);

    private String json = null;

    public MuForeignActivityParameters() {
        this.json = "";
    }

    public MuForeignActivityParameters(String json) {
        this.json = json;
    }

    public MuForeignActivityParameters(Reader reader) {
        try {
            json = IOUtils.toString(reader);
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
        return null == json || json.length() == 0;
    }

    /**
     * Creates a MuActivityParameters from a JSON stream.
     * @param reader JSON stream
     * @return MuActivityParameters made from JSON stream
     */
    public static MuForeignActivityParameters fromReader(Reader reader) {
        return new MuForeignActivityParameters(reader);
    }

    /**
     * Creates a JSON stream from a MuActivityParameters
     * @return Reader a JSON stream made from this object
     */
    @Override
    public Reader toReader() {
        return new StringReader(json);
    }

    @Override
    public String asJson() {
        return json;
    }

    @Override
    public String toString() {
        StringBuffer buf = new StringBuffer(getClass().getName());
        buf.append("(\"").append(json).append("\")");
        return buf.toString();
    }
}
