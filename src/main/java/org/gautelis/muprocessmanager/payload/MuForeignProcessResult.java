/*
 * Copyright (C) 2017-2018 Frode Randers
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

import com.google.gson.*;
import org.gautelis.muprocessmanager.MuProcess;
import org.gautelis.muprocessmanager.MuProcessResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Reader;
import java.io.Serializable;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.function.Consumer;

/**
 * Wraps results from a {@link MuProcess}.
 */
public class MuForeignProcessResult implements MuProcessResult, Serializable {
    private static final long serialVersionUID = 1L;
    private static final Logger log = LoggerFactory.getLogger(MuForeignProcessResult.class);
    private static final Gson gson = new GsonBuilder().create();

    /* package private */ static class ActivityResults extends ArrayList</* JSON */ String> {}
    private final ActivityResults results;

    public MuForeignProcessResult() {
        results = new ActivityResults();
    }

    public MuForeignProcessResult(Reader reader) {
        results = gson.fromJson(reader, ActivityResults.class);
    }

    /**
     * See {@link ArrayList#add(Object)}
     */
    public void add(String value) {
        results.add(value);
    }

    /**
     * See {@link ArrayList#remove(int)}
     */
    public String remove(int i) {
        return results.remove(i);
    }

    /**
     * See {@link ArrayList#get(int)}
     */
    public String get(int index) {
        return results.get(index);
    }

    /**
     * See {@link ArrayList#forEach(Consumer)}
     */
    public void forEach(Consumer<String> action) {
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
     * Creates a MuForeignProcessResult from a JSON stream.
     * @param reader JSON stream
     * @return MuActivityParameters made from JSON stream
     */
    public static MuForeignProcessResult fromReader(final Reader reader) {
        return new MuForeignProcessResult(reader);
    }

    /**
     * Creates a JSON stream from a MuProcessResult
     * @return Reader a JSON stream made from this object
     */
    @Override
    public Reader toReader() {
        return new StringReader(toJson());
    }

    @Override
    public String toJson() {
        return gson.toJson(results);
    }

    @Override
    public String toString() {
        StringBuilder buf = new StringBuilder(getClass().getName());
        buf.append("(\"").append(toJson()).append("\")");
        return buf.toString();
    }
}
