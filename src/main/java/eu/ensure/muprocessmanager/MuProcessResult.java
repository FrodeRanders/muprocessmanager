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

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.Reader;
import java.util.ArrayList;
import java.util.Arrays;

/**
 * Wraps results from a {@link MuProcess}. This is simply a collection of
 * parts.
 * <p>
 * The individual parts have to be serializable, as the whole thing is persisted
 * to database ({@link MuPersistentLog} takes care of this) as a JSON object.
 */
public class MuProcessResult extends ArrayList<Object> {

    private static final Gson gson = new GsonBuilder().disableHtmlEscaping().create();

    public MuProcessResult() {}

    public MuProcessResult(Object... result) {
        this.addAll(Arrays.asList(result));
    }

    public static MuProcessResult fromReader(final Reader reader) {
        return gson.fromJson(reader, MuProcessResult.class);
    }

    @Override
    public String toString() {
        StringBuffer buf = new StringBuffer(getClass().getName());
        buf.append("[");
        forEach((v) -> buf.append("{").append(v).append("}"));
        buf.append("]");
        return buf.toString();
    }
}
