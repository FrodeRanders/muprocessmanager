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
package org.gautelis.muprocessmanager;

import java.io.Reader;

/**
 * Wraps results from a {@link MuProcess}.
 * <p>
 * The individual parts have to be serializable, as the whole thing is persisted
 * to database ({@link MuPersistentLog} takes care of this) as a JSON object.
 */
public interface MuProcessResult {
    default boolean isEmpty() {
        return true;
    }

    default boolean isNative() {
        return false;
    }

    /**
     * Creates a JSON stream from a MuProcessResult
     * @return Reader a JSON stream made from this object
     */
    Reader toReader();

    /**
     * Retrns internal representation as JSON
     * @return JSON representation
     */
    String toJson();
}
