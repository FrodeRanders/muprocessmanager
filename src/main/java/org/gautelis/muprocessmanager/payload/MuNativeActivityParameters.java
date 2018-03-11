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

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.gautelis.muprocessmanager.MuActivity;
import org.gautelis.muprocessmanager.MuActivityParameters;
import org.gautelis.muprocessmanager.MuPersistentLog;

import java.io.Reader;
import java.io.Serializable;
import java.io.StringReader;
import java.util.HashMap;
import java.util.function.BiConsumer;

/**
 * Wraps native parameters to a {@link MuActivity}.
 * <p>
 * The value part has to be serializable, as the whole thing is persisted
 * to database ({@link MuPersistentLog} takes care of this) as a JSON object.
 */
public class MuNativeActivityParameters implements MuActivityParameters, Serializable {
    private static final long serialVersionUID = 1L;
    private static final Gson gson = new GsonBuilder().create();

    private final HashMap<String, Object> parameters;

    public MuNativeActivityParameters() {
        this.parameters =  new HashMap<>();
    }

    public MuNativeActivityParameters(HashMap<String, Object> parameters) {
        this.parameters = parameters;
    }

    @Override
    public boolean isNative() { return true; }

    /**
     * See {@link HashMap#put(Object, Object)}
     */
    public void put(String key, Object value) {
        parameters.put(key, value);
    }

    /**
     * See {@link HashMap#get(Object)}
     */
    public Object get(String key) {
        return parameters.get(key);
    }

    /**
     * See {@link HashMap#forEach(BiConsumer)}
     */
    public void forEach(BiConsumer<String, Object> action) {
        parameters.forEach(action);
    }

    /**
     * See {@link HashMap#isEmpty()}
     */
    public boolean isEmpty() {
        return parameters.isEmpty();
    }

    /**
     * Creates a MuActivityParameters from a JSON stream.
     * @param reader JSON stream
     * @return MuActivityParameters made from JSON stream
     */
    public static MuNativeActivityParameters fromReader(Reader reader) {
        @SuppressWarnings("unchecked")
        MuNativeActivityParameters parameters = new MuNativeActivityParameters(gson.fromJson(reader, HashMap.class));
        return parameters;
    }

    /**
     * Creates a JSON stream from a MuActivityParameters
     * @return Reader a JSON stream made from this object
     */
    public Reader toReader() {
        return new StringReader(asJson());
    }

    /**
     * Retrns internal representation as JSON
     * @return JSON representation
     */
    public String asJson() {
        return gson.toJson(parameters);
    }

    @Override
    public String toString() {
        StringBuffer buf = new StringBuffer(getClass().getName());
        buf.append("[");
        parameters.forEach((k, v) -> buf.append("{key=\"").append(k).append("\" value=\"").append(v).append("\"}"));
        buf.append("]");
        return buf.toString();
    }
}
