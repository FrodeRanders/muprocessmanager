package eu.ensure.muprocessmanager;

import eu.ensure.vopn.lang.Configurable;

public interface MuProcessManagementPolicy {

    @Configurable(property = "minutes-to-track-process")
    int minutesToTrackProcess();

    @Configurable(property = "minutes-before-assuming-process-stuck")
    int minutesBeforeAssumingProcessStuck();

    @Configurable(property = "seconds-between-recovery-attempts")
    int secondsBetweenRecoveryAttempts();

    @Configurable(property = "seconds-between-recompensation-attempts")
    int secondsBetweenRecompensationAttempts();

    @Configurable(property = "seconds-between-logging-statistics")
    int secondsBetweenLoggingStatistics();

    @Configurable(property = "accept-compensation-failure", value = "true")
    boolean acceptCompensationFailure();

    @Configurable(property = "number-of-recovery-threads")
    int numberOfRecoveryThreads();
}
