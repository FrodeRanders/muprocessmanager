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
package org.gautelis.muprocessmanager;

import java.util.HashMap;

/**
 * Wraps previous state (whatever that may be) before acted upon by an {@link MuForwardBehaviour activity},
 * and possibly reinstated by a {@link MuBackwardBehaviour compensation}. This is simply a collection of
 * key and value pairs.
 * <p>
 * The value part has to be serializable, as the whole thing is persisted
 * to database ({@link MuPersistentLog} takes care of this) as a JSON object.
 */
public class MuProcessState extends HashMap<String, Object> {

    @Override
    public String toString() {
        StringBuffer buf = new StringBuffer(getClass().getName());
        buf.append("[");
        forEach((k, v) -> buf.append("{key=\"").append(k).append("\" value=\"").append(v).append("\"}"));
        buf.append("]");
        return buf.toString();
    }
}
