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
package org.gautelis.muprocessmanager;

import java.io.Reader;
import java.io.Serializable;

/**
 * Wraps parameters to a {@link MuActivity}.
 */
public interface MuActivityParameters extends Serializable {

    default boolean isEmpty() {
        return true;
    }

    default boolean isNative() {
        return false;
    }

    /**
     * Creates a JSON stream from a MuActivityParameters
     *
     * @return Reader a JSON stream made from this object
     */
    Reader toReader();

    /**
     * Retrns internal representation as JSON
     * @return JSON representation
     */
    String toJson();
}