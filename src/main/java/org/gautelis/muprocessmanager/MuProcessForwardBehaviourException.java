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

/**
 * An exception occurring during {@link MuForwardBehaviour} of a {@link MuActivity}.
 */
public class MuProcessForwardBehaviourException extends MuProcessException {

    public MuProcessForwardBehaviourException(String info) {
        super(info);
    }

    public MuProcessForwardBehaviourException(String info, Throwable t) {
        super(info, t);
    }
}
