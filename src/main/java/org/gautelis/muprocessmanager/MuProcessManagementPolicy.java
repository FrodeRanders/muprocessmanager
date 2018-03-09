package org.gautelis.muprocessmanager;

import org.gautelis.vopn.lang.Configurable;

/**
 * Policy regulating the micro process manager handling of processes, in terms of specific
 * configuration of thread pool as well as timings for individual state transitions
 * (described <a href="file:doc-files/microprocess-manager-states-description.png">here</a>).
 * <p>
 * <a href="file:doc-files/microprocess-manager-states-configuration.png">
 *    <img src="file:doc-files/microprocess-manager-states-configuration.png" alt="configuration">
 * </a>
 */
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
