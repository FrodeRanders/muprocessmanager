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

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.util.*;

public class MuProcessDetails {

    // Time specified relative to local timezone
    private static final Gson gson =
            new GsonBuilder().setDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS").create();

    public static class MuActivityDetails {
        private final int stepId;
        private final int retries;
        private final MuActivityState preState;

        private MuActivityDetails(final int stepId, final int retries, final MuActivityState preState) {
            this.stepId = stepId;
            this.retries = retries;
            this.preState = preState;
        }

        public int getStepId() {
            return stepId;
        }

        public int getRetries() {
            return retries;
        }

        public Optional<MuActivityState> getPreState() {
            return Optional.ofNullable(preState);
        }
    }

    private final String correlationId;
    private final int processId;
    private final MuProcessState state;
    private final Date created;
    private final Date modified;
    private final Collection<MuActivityDetails> activityDetails = new LinkedList<>();

    /* package private */
    MuProcessDetails(
            final String correlationId, final int processId, final MuProcessState state, final Date created, final Date modified
    ) {
        Objects.requireNonNull(correlationId, "correlationId");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(created, "created");
        Objects.requireNonNull(modified, "modified");

        this.correlationId = correlationId;
        this.processId = processId;
        this.state = state;
        this.created = created;
        this.modified = modified;
    }

    public String getCorrelationId() {
        return correlationId;
    }

    public int getProcessId() {
        return processId;
    }

    public MuProcessState getState() {
        return state;
    }

    public Date getCreated() {
        return created;
    }

    public Date getModified() {
        return modified;
    }

    public Collection<MuActivityDetails> getActivityDetails() {
        return Collections.unmodifiableCollection(activityDetails);
    }

    /* package private */
    void addActivityDetails(int stepId, int retries, MuActivityState preState) {
        activityDetails.add(new MuActivityDetails(stepId, retries, preState));
    }

    public String toJson() {
        return gson.toJson(this);
    }
}
