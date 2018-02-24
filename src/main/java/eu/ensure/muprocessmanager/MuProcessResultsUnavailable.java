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
 * An exception occurring when trying to claim a {@link MuProcessResult} from an unsuccessful process.
 * Process results are available for successful processes under a period of time after finishing
 * a {@link MuProcess} (after a call to {@link MuProcess#finished(MuProcessResult)}).
 */
public class MuProcessResultsUnavailable extends MuProcessException {

    public MuProcessResultsUnavailable(String info) {
        super(info);
    }

    public MuProcessResultsUnavailable(String info, Throwable t) {
        super(info, t);
    }
}
