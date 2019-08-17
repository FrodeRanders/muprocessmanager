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
import org.gautelis.muprocessmanager.MuPersistentLog;
import org.gautelis.muprocessmanager.MuProcess;
import org.gautelis.muprocessmanager.MuProcessResult;

import java.io.Reader;
import java.io.Serializable;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.function.Consumer;

/**
 * Wraps results from a {@link MuProcess}.
 * <p>
 * The individual parts have to be serializable, as the whole thing is persisted
 * to database ({@link MuPersistentLog} takes care of this) as a JSON object.
 */
public class MuNativeProcessResult implements MuProcessResult, Serializable {
    private static final long serialVersionUID = 1L;

    private static final Gson gson = new GsonBuilder().create();

    /* package private */ static class ActivityResults extends ArrayList<Object> {}
    private final ActivityResults results;

    public MuNativeProcessResult() {
        results = new ActivityResults();
    }

    public MuNativeProcessResult(Object... result) {
        this();
        this.results.addAll(Arrays.asList(result));
    }

    public MuNativeProcessResult(ActivityResults results) {
        this.results = results;
    }

    @Override
    public boolean isNative() {
        return true;
    }

    /**
     * See {@link ArrayList#add(Object)}
     */
    public void add(Object value) {
        results.add(value);
    }

    /**
     * See {@link ArrayList#remove(int)}
     */
    public Object remove(int i) {
        return results.remove(i);
    }

    /**
     * See {@link ArrayList#get(int)}
     */
    public Object get(int index) {
        return results.get(index);
    }

    /**
     * See {@link ArrayList#forEach(Consumer)}
     */
    public void forEach(Consumer<Object> action) {
        results.forEach(action);
    }

    /**
     * See {@link ArrayList#isEmpty()}
     */
    @Override
    public boolean isEmpty() {
        return results.isEmpty();
    }

    /**
     * Creates a MuProcessResult from a JSON stream.
     * @param reader JSON stream
     * @return MuActivityParameters made from JSON stream
     */
    public static MuNativeProcessResult fromReader(final Reader reader) {
        @SuppressWarnings("unchecked")
        MuNativeProcessResult result = new MuNativeProcessResult(gson.fromJson(reader, ActivityResults.class));
        return result;
    }

    /**
     * Creates a JSON stream from a MuProcessResult
     * @return Reader a JSON stream made from this object
     */
    @Override
    public Reader toReader() {
        return new StringReader(toJson());
    }

    /**
     * Returns internal representation as JSON
     * @return JSON representation
     */
    @Override
    public String toJson() {
        return gson.toJson(results);
    }

    @Override
    public String toString() {
        StringBuffer buf = new StringBuffer(getClass().getName());
        buf.append("[");
        results.forEach((v) -> buf.append("{").append(v).append("}"));
        buf.append("]");
        return buf.toString();
    }
}
