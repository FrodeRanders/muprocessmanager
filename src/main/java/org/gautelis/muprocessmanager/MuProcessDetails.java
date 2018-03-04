package org.gautelis.muprocessmanager;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.util.*;

public class MuProcessDetails {

    private static final Gson gson = new GsonBuilder().disableHtmlEscaping().create();

    public class MuActivityDetails {
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
    private final MuProcessStatus status;
    private final Date created;
    private final Date modified;
    private final Collection<MuActivityDetails> activityDetails = new LinkedList<>();

    /* package private */ MuProcessDetails(
            final String correlationId, final int processId, final MuProcessStatus status, final Date created, final Date modified
    ) {
        this.correlationId = correlationId;
        this.processId = processId;
        this.status = status;
        this.created = created;
        this.modified = modified;
    }

    public String getCorrelationId() {
        return correlationId;
    }

    public int getProcessId() {
        return processId;
    }

    public MuProcessStatus getStatus() {
        return status;
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

    /* package private */ void addStepDetails(int stepId, int retries, MuActivityState preState) {
        activityDetails.add(new MuActivityDetails(stepId, retries, preState));
    }

    public String asJson() {
        return gson.toJson(this);
    }
}
