/*
 * Copyright (C) 2017-2019 Frode Randers
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
import org.gautelis.muprocessmanager.MuActivityState;
import org.gautelis.muprocessmanager.MuBackwardBehaviour;
import org.gautelis.muprocessmanager.MuForwardBehaviour;
import org.gautelis.muprocessmanager.MuPersistentLog;

import java.io.Reader;
import java.io.Serializable;
import java.io.StringReader;
import java.util.HashMap;
import java.util.function.BiConsumer;

/**
 * Wraps previous state (whatever that may be) before acted upon by an {@link MuForwardBehaviour activity},
 * and possibly reinstated by a {@link MuBackwardBehaviour compensation}.
 * <p>
 * The value part has to be serializable, as the whole thing is persisted
 * to database ({@link MuPersistentLog} takes care of this) as a JSON object.
 */
public class MuNativeActivityState implements MuActivityState, Serializable {
    private static final long serialVersionUID = 1L;
    private static final Gson gson = new GsonBuilder().create();

    private final HashMap<String, Object> state;

    public MuNativeActivityState() {
        state = new HashMap<>();
    }

    public MuNativeActivityState(HashMap<String, Object> state) {
        this.state = state;
    }

    @Override
    public boolean isNative() {
        return true;
    }

    /**
     * See {@link HashMap#put(Object, Object)}
     */
    public void put(String key, Object value) {
        state.put(key, value);
    }

    /**
     * See {@link HashMap#get(Object)}
     */
    public Object get(String key) {
        return state.get(key);
    }

    /**
     * See {@link HashMap#forEach(BiConsumer)}
     */
    public void forEach(BiConsumer<String, Object> action) {
        state.forEach(action);
    }

    /**
     * See {@link HashMap#isEmpty()}
     */
    @Override
    public boolean isEmpty() {
        return state.isEmpty();
    }

    /**
     * Creates a MuActivityState from a JSON stream.
     * @param reader JSON stream
     * @return MuActivityParameters made from JSON stream
     */
    public static MuNativeActivityState fromReader(final Reader reader) {
        @SuppressWarnings("unchecked")
        MuNativeActivityState state = new MuNativeActivityState(gson.fromJson(reader, HashMap.class));
        return state;
    }

    /**
     * Creates a JSON stream from a MuActivityState
     * @return Reader a JSON stream made from this object
     */
    @Override
    public Reader toReader() {
        return new StringReader(gson.toJson(state));
    }

    /**
     * Retrns internal representation as JSON
     * @return JSON representation
     */
    @Override
    public String toJson() {
        return gson.toJson(state);
    }

    @Override
    public String toString() {
        StringBuffer buf = new StringBuffer(getClass().getName());
        buf.append("[");
        state.forEach((k, v) -> buf.append("{key=\"").append(k).append("\" value=\"").append(v).append("\"}"));
        buf.append("]");
        return buf.toString();
    }
}
