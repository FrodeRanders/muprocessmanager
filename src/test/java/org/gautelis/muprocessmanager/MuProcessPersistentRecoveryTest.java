/*
 * Copyright (C) 2017-2026 Frode Randers
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

import org.gautelis.muprocessmanager.payload.MuNativeActivityParameters;
import org.junit.Test;

import javax.sql.DataSource;
import java.util.Collection;
import java.util.Optional;
import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class MuProcessPersistentRecoveryTest extends AbstractMuProcessManagerTest {
    @Test
    public void testOnlyCompensateIfTransactionWasSuccessfulSkipsFailedStep() throws MuProcessException {
        MuProcessManagementPolicy policy = policy()
                .onlyCompensateIfTransactionWasSuccessful(true)
                .build();
        DataSource dataSource = MuProcessManagerFactory.getDefaultDataSource(uniqueDbName("mu_process_manager_policy_"));
        MuProcessManagerFactory.prepareInternalDatabase(dataSource);
        MuProcessManager policyManager = MuProcessManagerFactory.getManager(
                dataSource,
                MuProcessManagerFactory.getDefaultSqlStatements(),
                policy
        );
        policyManager.start();

        try {
            BackwardSuccessCounter.reset();
            BackwardFailCounter.reset();

            String correlationId = UUID.randomUUID().toString();
            MuProcess process = policyManager.newProcess(correlationId);
            MuNativeActivityParameters parameters = new MuNativeActivityParameters();

            process.execute(c -> true, new BackwardSuccessCounter(), parameters);
            try {
                process.execute(c -> false, new BackwardFailCounter(), parameters);
                fail("Expected forward failure to trigger compensation");
            } catch (MuProcessForwardBehaviourException expected) {
            }

            assertEquals(1, BackwardSuccessCounter.CALLS.get());
            assertEquals(0, BackwardFailCounter.CALLS.get());
            assertEquals(MuProcessState.COMPENSATED, policyManager.getProcessState(correlationId).get());
        } finally {
            policyManager.stop();
        }
    }

    @Test
    public void testRecoverStuckProcessCompensates() throws Exception {
        ManagedPair pair = newManagedPair(
                "mu_process_manager_recover_",
                policy()
                        .minutesToTrackProcess(1)
                        .minutesBeforeAssumingProcessStuck(0)
                        .secondsBetweenRecoveryAttempts(1)
                        .secondsBetweenRecompensationAttempts(1)
                        .build()
        );
        String correlationId = UUID.randomUUID().toString();
        MuProcess process = pair.syncManager.newProcess(correlationId);
        process.execute(c -> true, new BackwardSuccess(), new MuNativeActivityParameters());

        Thread.sleep(10);

        pair.asyncManager.start();
        try {
            pair.asyncManager.recover();
            assertEquals(MuProcessState.COMPENSATED, awaitProcessState(pair.syncManager, correlationId, MuProcessState.COMPENSATED, 5000));
        } finally {
            pair.asyncManager.stop();
        }
    }

    @Test
    public void testRecoverCompensationFailedAbandonsWhenNotAllowed() throws Exception {
        ManagedPair pair = newManagedPair(
                "mu_process_manager_abandon_",
                policy()
                        .minutesToTrackProcess(1)
                        .minutesBeforeAssumingProcessStuck(1)
                        .secondsBetweenRecoveryAttempts(1)
                        .secondsBetweenRecompensationAttempts(1)
                        .acceptCompensationFailure(false)
                        .build()
        );
        String correlationId = UUID.randomUUID().toString();
        MuProcess process = pair.syncManager.newProcess(correlationId);
        MuNativeActivityParameters parameters = new MuNativeActivityParameters();

        process.execute(c -> true, new BackwardFail(), parameters);
        try {
            process.execute(c -> false, new BackwardSuccess(), parameters);
            fail("Expected compensation failure to throw");
        } catch (MuProcessBackwardBehaviourException expected) {
        }

        pair.asyncManager.start();
        try {
            pair.asyncManager.recover();
            assertEquals(MuProcessState.ABANDONED, awaitProcessState(pair.syncManager, correlationId, MuProcessState.ABANDONED, 5000));
        } finally {
            pair.asyncManager.stop();
        }
    }

    @Test
    public void testRecoverRemovesRetiredProcesses() throws Exception {
        ManagedPair pair = newManagedPair(
                "mu_process_manager_retire_",
                policy()
                        .minutesToTrackProcess(0)
                        .minutesBeforeAssumingProcessStuck(1)
                        .secondsBetweenRecoveryAttempts(1)
                        .secondsBetweenRecompensationAttempts(1)
                        .build()
        );
        MuNativeActivityParameters parameters = new MuNativeActivityParameters();

        String successfulId = UUID.randomUUID().toString();
        MuProcess successful = pair.syncManager.newProcess(successfulId);
        successful.execute(c -> true, new BackwardSuccess(), parameters);
        successful.finished();

        String compensatedId = UUID.randomUUID().toString();
        MuProcess compensated = pair.syncManager.newProcess(compensatedId);
        compensated.execute(c -> true, new BackwardSuccess(), parameters);
        try {
            compensated.execute(c -> false, new BackwardSuccess(), parameters);
            fail("Expected forward failure to trigger compensation");
        } catch (MuProcessForwardBehaviourException expected) {
        }

        Thread.sleep(10);

        pair.asyncManager.start();
        try {
            pair.asyncManager.recover();
            assertTrue(awaitProcessMissing(pair.syncManager, successfulId, 5000));
            assertTrue(awaitProcessMissing(pair.syncManager, compensatedId, 5000));
        } finally {
            pair.asyncManager.stop();
        }
    }

    @Test
    public void testRecoverRemovesStuckNewProcess() throws Exception {
        ManagedPair pair = newManagedPair(
                "mu_process_manager_new_",
                policy()
                        .minutesToTrackProcess(1)
                        .minutesBeforeAssumingProcessStuck(0)
                        .secondsBetweenRecoveryAttempts(1)
                        .secondsBetweenRecompensationAttempts(1)
                        .build()
        );
        MuPersistentLog log = new MuPersistentLog(pair.dataSource, pair.sqlStatements, true);
        MuProcess process = new MuProcess(UUID.randomUUID().toString(), log, true, true, false);
        log.pushProcess(process);

        Thread.sleep(10);

        pair.asyncManager.start();
        try {
            pair.asyncManager.recover();
            assertTrue(awaitProcessMissing(pair.syncManager, process.getCorrelationId(), 5000));
        } finally {
            pair.asyncManager.stop();
        }
    }

    @Test
    public void testProcessDetailsAndAbandonedDetails() throws Exception {
        ManagedPair pair = newManagedPair(
                "mu_process_manager_details_",
                policy()
                        .minutesToTrackProcess(1)
                        .minutesBeforeAssumingProcessStuck(1)
                        .secondsBetweenRecoveryAttempts(1)
                        .secondsBetweenRecompensationAttempts(1)
                        .acceptCompensationFailure(false)
                        .build()
        );
        String correlationId = UUID.randomUUID().toString();
        MuProcess process = pair.syncManager.newProcess(correlationId);
        MuNativeActivityParameters parameters = new MuNativeActivityParameters();

        process.execute(c -> true, new BackwardFail(), parameters);
        try {
            process.execute(c -> false, new BackwardSuccess(), parameters);
            fail("Expected compensation failure to throw");
        } catch (MuProcessBackwardBehaviourException expected) {
        }

        pair.asyncManager.start();
        try {
            pair.asyncManager.recover();
            assertEquals(MuProcessState.ABANDONED, awaitProcessState(pair.syncManager, correlationId, MuProcessState.ABANDONED, 5000));

            Optional<MuProcessDetails> details = pair.syncManager.getProcessDetails(correlationId);
            assertTrue(details.isPresent());
            assertEquals(MuProcessState.ABANDONED, details.get().getState());
            assertTrue(details.get().getActivityDetails().size() >= 1);

            Collection<MuProcessDetails> abandoned = pair.syncManager.getAbandonedProcessDetails();
            boolean found = false;
            for (MuProcessDetails item : abandoned) {
                if (correlationId.equals(item.getCorrelationId())) {
                    found = true;
                    break;
                }
            }
            assertTrue(found);
        } finally {
            pair.asyncManager.stop();
        }
    }

    @Test
    public void testActivityRetriesIncreaseAfterRecovery() throws Exception {
        ManagedPair pair = newManagedPair(
                "mu_process_manager_retries_",
                policy()
                        .minutesToTrackProcess(1)
                        .minutesBeforeAssumingProcessStuck(1)
                        .secondsBetweenRecoveryAttempts(1)
                        .secondsBetweenRecompensationAttempts(0)
                        .build()
        );
        String correlationId = UUID.randomUUID().toString();
        MuProcess process = pair.syncManager.newProcess(correlationId);
        MuNativeActivityParameters parameters = new MuNativeActivityParameters();

        process.execute(c -> true, new BackwardSuccess(), parameters);
        try {
            process.execute(c -> false, new BackwardFail(), parameters);
            fail("Expected compensation failure to throw");
        } catch (MuProcessBackwardBehaviourException expected) {
        }

        Optional<MuProcessDetails> initialDetails = pair.syncManager.getProcessDetails(correlationId);
        assertTrue(initialDetails.isPresent());
        int initialRetries = maxRetries(initialDetails.get());
        assertTrue(initialRetries >= 1);

        pair.asyncManager.start();
        try {
            pair.asyncManager.recover();
            pair.asyncManager.recover();

            int updatedRetries = awaitValue(
                    "activity retries for " + correlationId + " to increase beyond " + initialRetries,
                    () -> {
                        Optional<MuProcessDetails> details = pair.syncManager.getProcessDetails(correlationId);
                        return details.isPresent() ? maxRetries(details.get()) : -1;
                    },
                    retries -> retries > initialRetries,
                    DEFAULT_AWAIT_TIMEOUT_MILLIS
            );
            assertTrue(updatedRetries > initialRetries);
        } finally {
            pair.asyncManager.stop();
        }
    }
}
