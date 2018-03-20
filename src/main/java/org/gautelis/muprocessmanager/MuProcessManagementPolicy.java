package org.gautelis.muprocessmanager;

import org.gautelis.muprocessmanager.payload.MuNativeActivityParameters;
import org.gautelis.muprocessmanager.payload.MuForeignActivityParameters;
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

    /**
     *
     * @return <strong>true</strong> if the manager may assume that we have a 'native data' process flow, suitable for Java
     * process flows, -or- <strong>false</strong> if the process manager should not make any assumptions about the
     * data flowing through a process (other than it being JSON).
     * <p>
     * In the native case, the manager will use
     * <ul>
     *     <li>{@link MuNativeActivityParameters native activity parameters}</li>
     * </ul>
     * <p>
     * In the foreign case, the manager will use
     * <ul>
     *     <li>{@link MuForeignActivityParameters foreign activity parameters}</li>
     * </ul>
     */
    @Configurable(property = "assume-native-process-data-flow", value = "true")
    boolean assumeNativeProcessDataFlow();
}
