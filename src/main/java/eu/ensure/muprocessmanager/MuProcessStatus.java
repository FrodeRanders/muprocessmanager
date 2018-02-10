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

/**
 * Please refer to the description of the success and failure modes in the
 * documentation.
 */
public enum MuProcessStatus {
    NEW(0),
    PROGRESSING(1),
    SUCCESSFUL(2),
    COMPENSATED(3),
    COMPENSATION_FAILED(4),
    ABANDONED(5);

    private final int status;

    MuProcessStatus(int status) {
        this.status = status;
    }

    public int toInt() {
        return status;
    }

    public static MuProcessStatus fromInt(int _status) {
        switch (_status) {
            case 0:
                return NEW;

            case 1:
                return PROGRESSING;

            case 2:
                return SUCCESSFUL;

            case 3:
                return COMPENSATED;

            case 4:
                return COMPENSATION_FAILED;

            case 5:
            default:
                return ABANDONED;
        }
    }

    @Override
    public String toString() {
        return name();
    }
}
