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
import org.gautelis.muprocessmanager.payload.MuNativeProcessResult;
import org.gautelis.muprocessmanager.payload.MuForeignActivityParameters;
import org.gautelis.muprocessmanager.payload.MuForeignProcessResult;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.TimeUnit;
import java.io.File;
import java.io.FileNotFoundException;
import java.util.Properties;
import java.util.Optional;
import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class MuProcessPersistentTest {
    private MuProcessManager manager;

    @Before
    public void setUp() throws MuProcessException {
        String dbName = "mu_process_manager_test_" + UUID.randomUUID().toString().replace("-", "");
        DataSource dataSource = MuProcessManagerFactory.getDefaultDataSource(dbName);
        MuProcessManagerFactory.prepareInternalDatabase(dataSource);
        manager = MuProcessManagerFactory.getManager(dataSource);
        manager.start();
    }

    @After
    public void tearDown() {
        if (manager != null) {
            manager.stop();
        }
    }

    @Test
    public void testSuccessfulProcessPersistsResult() throws MuProcessException {
        String correlationId = UUID.randomUUID().toString();
        MuProcess process = manager.newProcess(correlationId);
        MuNativeActivityParameters parameters = new MuNativeActivityParameters();

        process.execute(
                c -> {
                    ((MuNativeProcessResult) c.getResult()).add("ok");
                    return true;
                },
                new BackwardSuccess(),
                parameters
        );

        process.finished();

        Optional<MuProcessState> state = manager.getProcessState(correlationId);
        assertTrue(state.isPresent());
        assertEquals(MuProcessState.SUCCESSFUL, state.get());

        Optional<MuProcessResult> result = manager.getProcessResult(correlationId);
        assertTrue(result.isPresent());
        MuNativeProcessResult nativeResult = (MuNativeProcessResult) result.get();
        assertEquals("ok", nativeResult.get(0));
    }

    @Test
    public void testForwardFailureMarksCompensated() throws MuProcessException {
        String correlationId = UUID.randomUUID().toString();
        MuProcess process = manager.newProcess(correlationId);
        MuNativeActivityParameters parameters = new MuNativeActivityParameters();

        process.execute(c -> true, new BackwardSuccess(), parameters);

        try {
            process.execute(c -> false, new BackwardSuccess(), parameters);
            fail("Expected forward failure to trigger compensation");
        }
        catch (MuProcessForwardBehaviourException expected) {
            // Expected: forward failed and compensation succeeded.
        }

        Optional<MuProcessState> state = manager.getProcessState(correlationId);
        assertTrue(state.isPresent());
        assertEquals(MuProcessState.COMPENSATED, state.get());
    }

    @Test
    public void testCompensationFailureMarksCompensationFailed() throws MuProcessException {
        String correlationId = UUID.randomUUID().toString();
        MuProcess process = manager.newProcess(correlationId);
        MuNativeActivityParameters parameters = new MuNativeActivityParameters();

        process.execute(c -> true, new BackwardFail(), parameters);

        try {
            process.execute(c -> false, new BackwardSuccess(), parameters);
            fail("Expected compensation failure to throw");
        }
        catch (MuProcessBackwardBehaviourException expected) {
            // Expected: compensation failed and process should be marked accordingly.
        }

        Optional<MuProcessState> state = manager.getProcessState(correlationId);
        assertTrue(state.isPresent());
        assertEquals(MuProcessState.COMPENSATION_FAILED, state.get());
    }

    @Test
    public void testResetProcessClearsFailedProcess() throws MuProcessException {
        String correlationId = UUID.randomUUID().toString();
        MuProcess process = manager.newProcess(correlationId);
        MuNativeActivityParameters parameters = new MuNativeActivityParameters();

        process.execute(c -> true, new BackwardFail(), parameters);

        try {
            process.execute(c -> false, new BackwardSuccess(), parameters);
            fail("Expected compensation failure to throw");
        }
        catch (MuProcessBackwardBehaviourException expected) {
            // Expected: compensation failed and process should be resettable.
        }

        Optional<Boolean> reset = manager.resetProcess(correlationId);
        assertTrue(reset.isPresent());
        assertEquals(Boolean.TRUE, reset.get());

        Optional<MuProcessState> state = manager.getProcessState(correlationId);
        assertTrue(!state.isPresent());
    }

    @Test
    public void testForwardOnlyFailureMarksCompensated() throws MuProcessException {
        String correlationId = UUID.randomUUID().toString();
        MuProcess process = manager.newProcess(correlationId);
        MuNativeActivityParameters parameters = new MuNativeActivityParameters();

        try {
            process.execute(c -> false, parameters);
            fail("Expected forward-only failure to throw");
        }
        catch (MuProcessForwardBehaviourException expected) {
            // Expected: forward failed and compensation (no-op) succeeded.
        }

        Optional<MuProcessState> state = manager.getProcessState(correlationId);
        assertTrue(state.isPresent());
        assertEquals(MuProcessState.COMPENSATED, state.get());
    }

    @Test
    public void testForwardOnlySuccessCanFinish() throws MuProcessException {
        String correlationId = UUID.randomUUID().toString();
        MuProcess process = manager.newProcess(correlationId);
        MuNativeActivityParameters parameters = new MuNativeActivityParameters();

        process.execute(c -> true, parameters);
        process.finished();

        Optional<MuProcessState> state = manager.getProcessState(correlationId);
        assertTrue(state.isPresent());
        assertEquals(MuProcessState.SUCCESSFUL, state.get());

        Optional<MuProcessResult> result = manager.getProcessResult(correlationId);
        assertTrue(!result.isPresent());
    }

    @Test
    public void testGetResultUnavailableWhenNotSuccessful() throws MuProcessException {
        String correlationId = UUID.randomUUID().toString();
        MuProcess process = manager.newProcess(correlationId);
        MuNativeActivityParameters parameters = new MuNativeActivityParameters();

        try {
            process.execute(c -> false, parameters);
            fail("Expected forward-only failure to throw");
        }
        catch (MuProcessForwardBehaviourException expected) {
            // Expected: process is not successful, result should be unavailable.
        }

        try {
            manager.getProcessResult(correlationId);
            fail("Expected result to be unavailable for non-successful process");
        }
        catch (MuProcessResultsUnavailable expected) {
            // Expected: only SUCCESSFUL processes have results.
        }
    }

    @Test
    public void testOnlyCompensateIfTransactionWasSuccessfulSkipsFailedStep() throws MuProcessException {
        String dbName = "mu_process_manager_policy_" + UUID.randomUUID().toString().replace("-", "");
        DataSource dataSource = MuProcessManagerFactory.getDefaultDataSource(dbName);
        MuProcessManagerFactory.prepareInternalDatabase(dataSource);

        MuProcessManagementPolicy policy = new MuProcessManagementPolicy() {
            @Override
            public int minutesToTrackProcess() {
                return 5;
            }

            @Override
            public int minutesBeforeAssumingProcessStuck() {
                return 5;
            }

            @Override
            public int secondsBetweenRecoveryAttempts() {
                return 30;
            }

            @Override
            public int secondsBetweenRecompensationAttempts() {
                return 30;
            }

            @Override
            public int secondsBetweenLoggingStatistics() {
                return 60;
            }

            @Override
            public boolean acceptCompensationFailure() {
                return true;
            }

            @Override
            public boolean onlyCompensateIfTransactionWasSuccessful() {
                return true;
            }

            @Override
            public int numberOfRecoveryThreads() {
                return 1;
            }

            @Override
            public boolean assumeNativeProcessDataFlow() {
                return true;
            }
        };

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
            }
            catch (MuProcessForwardBehaviourException expected) {
                // Expected: compensation should run only for successful steps.
            }

            assertEquals(1, BackwardSuccessCounter.CALLS.get());
            assertEquals(0, BackwardFailCounter.CALLS.get());

            Optional<MuProcessState> state = policyManager.getProcessState(correlationId);
            assertTrue(state.isPresent());
            assertEquals(MuProcessState.COMPENSATED, state.get());
        }
        finally {
            policyManager.stop();
        }
    }

    @Test
    public void testRecoverStuckProcessCompensates() throws Exception {
        String dbName = "mu_process_manager_recover_" + UUID.randomUUID().toString().replace("-", "");
        DataSource dataSource = MuProcessManagerFactory.getDefaultDataSource(dbName);
        MuProcessManagerFactory.prepareInternalDatabase(dataSource);
        Properties sqlStatements = MuProcessManagerFactory.getDefaultSqlStatements();

        MuProcessManagementPolicy policy = new MuProcessManagementPolicy() {
            @Override
            public int minutesToTrackProcess() {
                return 1;
            }

            @Override
            public int minutesBeforeAssumingProcessStuck() {
                return 0;
            }

            @Override
            public int secondsBetweenRecoveryAttempts() {
                return 1;
            }

            @Override
            public int secondsBetweenRecompensationAttempts() {
                return 1;
            }

            @Override
            public int secondsBetweenLoggingStatistics() {
                return 60;
            }

            @Override
            public boolean acceptCompensationFailure() {
                return true;
            }

            @Override
            public boolean onlyCompensateIfTransactionWasSuccessful() {
                return false;
            }

            @Override
            public int numberOfRecoveryThreads() {
                return 1;
            }

            @Override
            public boolean assumeNativeProcessDataFlow() {
                return true;
            }
        };

        MuSynchronousManagerImpl syncManager = new MuSynchronousManagerImpl(dataSource, sqlStatements, policy);
        MuAsynchronousManagerImpl asyncManager = new MuAsynchronousManagerImpl(dataSource, sqlStatements, policy);

        String correlationId = UUID.randomUUID().toString();
        MuProcess process = syncManager.newProcess(correlationId);
        MuNativeActivityParameters parameters = new MuNativeActivityParameters();
        process.execute(c -> true, new BackwardSuccess(), parameters);

        Thread.sleep(10);

        asyncManager.start();
        try {
            asyncManager.recover();

            MuProcessState recoveredState = null;
            long deadline = System.currentTimeMillis() + 5000;
            while (System.currentTimeMillis() < deadline) {
                Optional<MuProcessState> state = syncManager.getProcessState(correlationId);
                if (state.isPresent()) {
                    recoveredState = state.get();
                    if (recoveredState == MuProcessState.COMPENSATED) {
                        break;
                    }
                }
                Thread.sleep(50);
            }

            assertEquals(MuProcessState.COMPENSATED, recoveredState);
        }
        finally {
            asyncManager.stop();
        }
    }

    @Test
    public void testRecoverCompensationFailedAbandonsWhenNotAllowed() throws Exception {
        String dbName = "mu_process_manager_abandon_" + UUID.randomUUID().toString().replace("-", "");
        DataSource dataSource = MuProcessManagerFactory.getDefaultDataSource(dbName);
        MuProcessManagerFactory.prepareInternalDatabase(dataSource);
        Properties sqlStatements = MuProcessManagerFactory.getDefaultSqlStatements();

        MuProcessManagementPolicy policy = new MuProcessManagementPolicy() {
            @Override
            public int minutesToTrackProcess() {
                return 1;
            }

            @Override
            public int minutesBeforeAssumingProcessStuck() {
                return 1;
            }

            @Override
            public int secondsBetweenRecoveryAttempts() {
                return 1;
            }

            @Override
            public int secondsBetweenRecompensationAttempts() {
                return 1;
            }

            @Override
            public int secondsBetweenLoggingStatistics() {
                return 60;
            }

            @Override
            public boolean acceptCompensationFailure() {
                return false;
            }

            @Override
            public boolean onlyCompensateIfTransactionWasSuccessful() {
                return false;
            }

            @Override
            public int numberOfRecoveryThreads() {
                return 1;
            }

            @Override
            public boolean assumeNativeProcessDataFlow() {
                return true;
            }
        };

        MuSynchronousManagerImpl syncManager = new MuSynchronousManagerImpl(dataSource, sqlStatements, policy);
        MuAsynchronousManagerImpl asyncManager = new MuAsynchronousManagerImpl(dataSource, sqlStatements, policy);

        String correlationId = UUID.randomUUID().toString();
        MuProcess process = syncManager.newProcess(correlationId);
        MuNativeActivityParameters parameters = new MuNativeActivityParameters();

        process.execute(c -> true, new BackwardFail(), parameters);

        try {
            process.execute(c -> false, new BackwardSuccess(), parameters);
            fail("Expected compensation failure to throw");
        }
        catch (MuProcessBackwardBehaviourException expected) {
            // Expected: compensation failed and should be abandoned on recovery.
        }

        asyncManager.start();
        try {
            asyncManager.recover();

            MuProcessState recoveredState = null;
            long deadline = System.currentTimeMillis() + 5000;
            while (System.currentTimeMillis() < deadline) {
                Optional<MuProcessState> state = syncManager.getProcessState(correlationId);
                if (state.isPresent()) {
                    recoveredState = state.get();
                    if (recoveredState == MuProcessState.ABANDONED) {
                        break;
                    }
                }
                Thread.sleep(50);
            }

            assertEquals(MuProcessState.ABANDONED, recoveredState);
        }
        finally {
            asyncManager.stop();
        }
    }

    @Test
    public void testRecoverRemovesRetiredProcesses() throws Exception {
        String dbName = "mu_process_manager_retire_" + UUID.randomUUID().toString().replace("-", "");
        DataSource dataSource = MuProcessManagerFactory.getDefaultDataSource(dbName);
        MuProcessManagerFactory.prepareInternalDatabase(dataSource);
        Properties sqlStatements = MuProcessManagerFactory.getDefaultSqlStatements();

        MuProcessManagementPolicy policy = new MuProcessManagementPolicy() {
            @Override
            public int minutesToTrackProcess() {
                return 0;
            }

            @Override
            public int minutesBeforeAssumingProcessStuck() {
                return 1;
            }

            @Override
            public int secondsBetweenRecoveryAttempts() {
                return 1;
            }

            @Override
            public int secondsBetweenRecompensationAttempts() {
                return 1;
            }

            @Override
            public int secondsBetweenLoggingStatistics() {
                return 60;
            }

            @Override
            public boolean acceptCompensationFailure() {
                return true;
            }

            @Override
            public boolean onlyCompensateIfTransactionWasSuccessful() {
                return false;
            }

            @Override
            public int numberOfRecoveryThreads() {
                return 1;
            }

            @Override
            public boolean assumeNativeProcessDataFlow() {
                return true;
            }
        };

        MuSynchronousManagerImpl syncManager = new MuSynchronousManagerImpl(dataSource, sqlStatements, policy);
        MuAsynchronousManagerImpl asyncManager = new MuAsynchronousManagerImpl(dataSource, sqlStatements, policy);

        String successfulId = UUID.randomUUID().toString();
        MuProcess successful = syncManager.newProcess(successfulId);
        MuNativeActivityParameters parameters = new MuNativeActivityParameters();
        successful.execute(c -> true, new BackwardSuccess(), parameters);
        successful.finished();

        String compensatedId = UUID.randomUUID().toString();
        MuProcess compensated = syncManager.newProcess(compensatedId);
        compensated.execute(c -> true, new BackwardSuccess(), parameters);
        try {
            compensated.execute(c -> false, new BackwardSuccess(), parameters);
            fail("Expected forward failure to trigger compensation");
        }
        catch (MuProcessForwardBehaviourException expected) {
            // Expected: compensated process.
        }

        Thread.sleep(10);

        asyncManager.start();
        try {
            asyncManager.recover();

            long deadline = System.currentTimeMillis() + 5000;
            Optional<MuProcessState> successfulState = Optional.of(MuProcessState.NEW);
            Optional<MuProcessState> compensatedState = Optional.of(MuProcessState.NEW);
            while (System.currentTimeMillis() < deadline) {
                successfulState = syncManager.getProcessState(successfulId);
                compensatedState = syncManager.getProcessState(compensatedId);
                if (!successfulState.isPresent() && !compensatedState.isPresent()) {
                    break;
                }
                Thread.sleep(50);
            }

            assertTrue(!successfulState.isPresent());
            assertTrue(!compensatedState.isPresent());
        }
        finally {
            asyncManager.stop();
        }
    }

    @Test
    public void testRecoverRemovesStuckNewProcess() throws Exception {
        String dbName = "mu_process_manager_new_" + UUID.randomUUID().toString().replace("-", "");
        DataSource dataSource = MuProcessManagerFactory.getDefaultDataSource(dbName);
        MuProcessManagerFactory.prepareInternalDatabase(dataSource);
        Properties sqlStatements = MuProcessManagerFactory.getDefaultSqlStatements();

        MuProcessManagementPolicy policy = new MuProcessManagementPolicy() {
            @Override
            public int minutesToTrackProcess() {
                return 1;
            }

            @Override
            public int minutesBeforeAssumingProcessStuck() {
                return 0;
            }

            @Override
            public int secondsBetweenRecoveryAttempts() {
                return 1;
            }

            @Override
            public int secondsBetweenRecompensationAttempts() {
                return 1;
            }

            @Override
            public int secondsBetweenLoggingStatistics() {
                return 60;
            }

            @Override
            public boolean acceptCompensationFailure() {
                return true;
            }

            @Override
            public boolean onlyCompensateIfTransactionWasSuccessful() {
                return false;
            }

            @Override
            public int numberOfRecoveryThreads() {
                return 1;
            }

            @Override
            public boolean assumeNativeProcessDataFlow() {
                return true;
            }
        };

        MuPersistentLog log = new MuPersistentLog(dataSource, sqlStatements, true);
        MuProcess process = new MuProcess(UUID.randomUUID().toString(), log, true, true, false);
        log.pushProcess(process);

        Thread.sleep(10);

        MuSynchronousManagerImpl syncManager = new MuSynchronousManagerImpl(dataSource, sqlStatements, policy);
        MuAsynchronousManagerImpl asyncManager = new MuAsynchronousManagerImpl(dataSource, sqlStatements, policy);

        asyncManager.start();
        try {
            asyncManager.recover();

            Optional<MuProcessState> state = Optional.of(MuProcessState.NEW);
            long deadline = System.currentTimeMillis() + 5000;
            while (System.currentTimeMillis() < deadline) {
                state = syncManager.getProcessState(process.getCorrelationId());
                if (!state.isPresent()) {
                    break;
                }
                Thread.sleep(50);
            }

            assertTrue(!state.isPresent());
        }
        finally {
            asyncManager.stop();
        }
    }

    @Test
    public void testConcurrentProcessesCompleteSuccessfully() throws Exception {
        int processCount = 10;
        ExecutorService executor = Executors.newFixedThreadPool(4);
        CountDownLatch latch = new CountDownLatch(processCount);
        List<String> correlationIds = new ArrayList<>();

        for (int i = 0; i < processCount; i++) {
            String correlationId = UUID.randomUUID().toString();
            correlationIds.add(correlationId);
            executor.execute(() -> {
                try {
                    MuProcess process = manager.newProcess(correlationId);
                    MuNativeActivityParameters parameters = new MuNativeActivityParameters();
                    process.execute(c -> true, parameters);
                    process.finished();
                }
                catch (MuProcessException ignore) {
                    // Best-effort test; failures will surface in state checks.
                }
                finally {
                    latch.countDown();
                }
            });
        }

        assertTrue(latch.await(10, TimeUnit.SECONDS));
        executor.shutdown();
        executor.awaitTermination(5, TimeUnit.SECONDS);

        for (String correlationId : correlationIds) {
            Optional<MuProcessState> state = manager.getProcessState(correlationId);
            assertTrue(state.isPresent());
            assertEquals(MuProcessState.SUCCESSFUL, state.get());
        }
    }

    @Test
    public void testNativePayloadRoundTrip() {
        MuNativeActivityParameters parameters = new MuNativeActivityParameters();
        parameters.put("name", "alpha");
        parameters.put("flag", true);
        parameters.put("ratio", 1.5d);

        MuNativeActivityParameters restored =
                MuNativeActivityParameters.fromReader(parameters.toReader());

        assertEquals("alpha", restored.get("name"));
        assertEquals(true, restored.get("flag"));
        assertEquals(1.5d, (Double) restored.get("ratio"), 0.0001d);

        MuNativeProcessResult result = new MuNativeProcessResult();
        result.add("value");
        result.add(2.0d);

        MuNativeProcessResult restoredResult =
                MuNativeProcessResult.fromReader(result.toReader());

        assertEquals("value", restoredResult.get(0));
        assertEquals(2.0d, (Double) restoredResult.get(1), 0.0001d);
    }

    @Test
    public void testForeignPayloadRoundTrip() {
        String payload = "{\"key\":\"value\"}";
        MuForeignActivityParameters parameters = new MuForeignActivityParameters(payload);
        MuForeignActivityParameters restored =
                MuForeignActivityParameters.fromReader(parameters.toReader());
        assertEquals(payload, restored.toJson());

        String resultsPayload = "[\"{\\\"a\\\":1}\",\"{\\\"b\\\":\\\"x\\\"}\"]";
        MuForeignProcessResult restoredResult =
                MuForeignProcessResult.fromReader(new java.io.StringReader(resultsPayload));

        assertEquals("{\"a\":1}", restoredResult.get(0));
        assertEquals("{\"b\":\"x\"}", restoredResult.get(1));
        assertEquals("[{\"a\":1},{\"b\":\"x\"}]", restoredResult.toJson());
    }

    @Test
    public void testResetProcessReturnsFalseForActiveOrSuccessful() throws MuProcessException {
        String progressingId = UUID.randomUUID().toString();
        MuProcess progressing = manager.newProcess(progressingId);
        MuNativeActivityParameters parameters = new MuNativeActivityParameters();
        progressing.execute(c -> true, parameters);

        try {
            Optional<Boolean> progressingReset = manager.resetProcess(progressingId);
            assertTrue(progressingReset.isPresent());
            assertEquals(Boolean.FALSE, progressingReset.get());
        }
        catch (MuProcessException expected) {
            assertTrue(expected.getMessage().startsWith("Failed to reset process"));
        }

        String successfulId = UUID.randomUUID().toString();
        MuProcess successful = manager.newProcess(successfulId);
        successful.execute(c -> true, new BackwardSuccess(), parameters);
        successful.finished();

        try {
            Optional<Boolean> successfulReset = manager.resetProcess(successfulId);
            assertTrue(successfulReset.isPresent());
            assertEquals(Boolean.FALSE, successfulReset.get());
        }
        catch (MuProcessException expected) {
            assertTrue(expected.getMessage().startsWith("Failed to reset process"));
        }
    }

    @Test
    public void testDuplicateCorrelationIdThrows() throws MuProcessException {
        String correlationId = UUID.randomUUID().toString();
        MuProcess first = manager.newProcess(correlationId);
        MuNativeActivityParameters parameters = new MuNativeActivityParameters();
        first.execute(c -> true, new BackwardSuccess(), parameters);
        first.finished();

        MuProcess duplicate = manager.newProcess(correlationId);
        try {
            duplicate.execute(c -> true, new BackwardSuccess(), parameters);
            fail("Expected duplicate correlation ID to fail");
        }
        catch (MuProcessForwardBehaviourException expected) {
            assertTrue(expected.getMessage().contains("Forward activity failed"));
        }

        Optional<MuProcessState> state = manager.getProcessState(correlationId);
        assertTrue(state.isPresent());
        assertEquals(MuProcessState.SUCCESSFUL, state.get());
    }

    @Test
    public void testProcessDetailsAndAbandonedDetails() throws Exception {
        String dbName = "mu_process_manager_details_" + UUID.randomUUID().toString().replace("-", "");
        DataSource dataSource = MuProcessManagerFactory.getDefaultDataSource(dbName);
        MuProcessManagerFactory.prepareInternalDatabase(dataSource);
        Properties sqlStatements = MuProcessManagerFactory.getDefaultSqlStatements();

        MuProcessManagementPolicy policy = new MuProcessManagementPolicy() {
            @Override
            public int minutesToTrackProcess() {
                return 1;
            }

            @Override
            public int minutesBeforeAssumingProcessStuck() {
                return 1;
            }

            @Override
            public int secondsBetweenRecoveryAttempts() {
                return 1;
            }

            @Override
            public int secondsBetweenRecompensationAttempts() {
                return 1;
            }

            @Override
            public int secondsBetweenLoggingStatistics() {
                return 60;
            }

            @Override
            public boolean acceptCompensationFailure() {
                return false;
            }

            @Override
            public boolean onlyCompensateIfTransactionWasSuccessful() {
                return false;
            }

            @Override
            public int numberOfRecoveryThreads() {
                return 1;
            }

            @Override
            public boolean assumeNativeProcessDataFlow() {
                return true;
            }
        };

        MuSynchronousManagerImpl syncManager = new MuSynchronousManagerImpl(dataSource, sqlStatements, policy);
        MuAsynchronousManagerImpl asyncManager = new MuAsynchronousManagerImpl(dataSource, sqlStatements, policy);

        String correlationId = UUID.randomUUID().toString();
        MuProcess process = syncManager.newProcess(correlationId);
        MuNativeActivityParameters parameters = new MuNativeActivityParameters();

        process.execute(c -> true, new BackwardFail(), parameters);
        try {
            process.execute(c -> false, new BackwardSuccess(), parameters);
            fail("Expected compensation failure to throw");
        }
        catch (MuProcessBackwardBehaviourException expected) {
            // Expected.
        }

        asyncManager.start();
        try {
            asyncManager.recover();

            Optional<MuProcessDetails> details = syncManager.getProcessDetails(correlationId);
            assertTrue(details.isPresent());
            assertEquals(MuProcessState.ABANDONED, details.get().getState());
            assertTrue(details.get().getActivityDetails().size() >= 1);

            Collection<MuProcessDetails> abandoned = syncManager.getAbandonedProcessDetails();
            boolean found = false;
            for (MuProcessDetails item : abandoned) {
                if (correlationId.equals(item.getCorrelationId())) {
                    found = true;
                    break;
                }
            }
            assertTrue(found);
        }
        finally {
            asyncManager.stop();
        }
    }

    @Test
    public void testFactoryConfigLoadErrors() throws Exception {
        File missing = new File("does-not-exist-" + UUID.randomUUID().toString() + ".xml");

        try {
            MuProcessManagerFactory.getDatabaseConfiguration(missing);
            fail("Expected missing file to throw");
        }
        catch (FileNotFoundException expected) {
            // Expected.
        }

        try {
            MuProcessManagerFactory.getSqlStatements(missing);
            fail("Expected missing file to throw");
        }
        catch (FileNotFoundException expected) {
            // Expected.
        }

        try {
            MuProcessManagerFactory.getManagementPolicy(missing);
            fail("Expected missing file to throw");
        }
        catch (FileNotFoundException expected) {
            // Expected.
        }

        try {
            MuProcessManagerFactory.getSqlStatements(MuSynchronousManagerImpl.class, "no-such-statements.xml");
            fail("Expected unknown resource to throw");
        }
        catch (IllegalArgumentException expected) {
            // Expected.
        }

        try {
            MuProcessManagerFactory.getManagementPolicy(MuSynchronousManagerImpl.class, "no-such-policy.xml");
            fail("Expected unknown resource to throw");
        }
        catch (IllegalArgumentException expected) {
            // Expected.
        }
    }

    @Test
    public void testActivityRetriesIncreaseAfterRecovery() throws Exception {
        String dbName = "mu_process_manager_retries_" + UUID.randomUUID().toString().replace("-", "");
        DataSource dataSource = MuProcessManagerFactory.getDefaultDataSource(dbName);
        MuProcessManagerFactory.prepareInternalDatabase(dataSource);
        Properties sqlStatements = MuProcessManagerFactory.getDefaultSqlStatements();

        MuProcessManagementPolicy policy = new MuProcessManagementPolicy() {
            @Override
            public int minutesToTrackProcess() {
                return 1;
            }

            @Override
            public int minutesBeforeAssumingProcessStuck() {
                return 1;
            }

            @Override
            public int secondsBetweenRecoveryAttempts() {
                return 1;
            }

            @Override
            public int secondsBetweenRecompensationAttempts() {
                return 0;
            }

            @Override
            public int secondsBetweenLoggingStatistics() {
                return 60;
            }

            @Override
            public boolean acceptCompensationFailure() {
                return true;
            }

            @Override
            public boolean onlyCompensateIfTransactionWasSuccessful() {
                return false;
            }

            @Override
            public int numberOfRecoveryThreads() {
                return 1;
            }

            @Override
            public boolean assumeNativeProcessDataFlow() {
                return true;
            }
        };

        MuSynchronousManagerImpl syncManager = new MuSynchronousManagerImpl(dataSource, sqlStatements, policy);
        MuAsynchronousManagerImpl asyncManager = new MuAsynchronousManagerImpl(dataSource, sqlStatements, policy);

        String correlationId = UUID.randomUUID().toString();
        MuProcess process = syncManager.newProcess(correlationId);
        MuNativeActivityParameters parameters = new MuNativeActivityParameters();

        process.execute(c -> true, new BackwardSuccess(), parameters);
        try {
            process.execute(c -> false, new BackwardFail(), parameters);
            fail("Expected compensation failure to throw");
        }
        catch (MuProcessBackwardBehaviourException expected) {
            // Expected: compensation failed and retries should be recorded.
        }

        Optional<MuProcessDetails> initialDetails = syncManager.getProcessDetails(correlationId);
        assertTrue(initialDetails.isPresent());
        int initialRetries = maxRetries(initialDetails.get());
        assertTrue(initialRetries >= 1);

        asyncManager.start();
        try {
            asyncManager.recover();
            asyncManager.recover();

            int updatedRetries = initialRetries;
            long deadline = System.currentTimeMillis() + 5000;
            while (System.currentTimeMillis() < deadline) {
                Optional<MuProcessDetails> details = syncManager.getProcessDetails(correlationId);
                if (details.isPresent()) {
                    updatedRetries = maxRetries(details.get());
                    if (updatedRetries > initialRetries) {
                        break;
                    }
                }
                Thread.sleep(50);
            }

            assertTrue(updatedRetries > initialRetries);
        }
        finally {
            asyncManager.stop();
        }
    }

    private static int maxRetries(MuProcessDetails details) {
        int max = 0;
        for (MuProcessDetails.MuActivityDetails activity : details.getActivityDetails()) {
            if (activity.getRetries() > max) {
                max = activity.getRetries();
            }
        }
        return max;
    }

    @Test
    public void testResetProcessAfterCompensatedStress() throws MuProcessException {
        int processCount = 5;
        List<String> correlationIds = new ArrayList<>();
        MuNativeActivityParameters parameters = new MuNativeActivityParameters();

        for (int i = 0; i < processCount; i++) {
            String correlationId = UUID.randomUUID().toString();
            correlationIds.add(correlationId);
            MuProcess process = manager.newProcess(correlationId);
            process.execute(c -> true, new BackwardSuccess(), parameters);
            try {
                process.execute(c -> false, new BackwardSuccess(), parameters);
                fail("Expected forward failure to trigger compensation");
            }
            catch (MuProcessForwardBehaviourException expected) {
                // Expected: process is compensated.
            }
        }

        for (String correlationId : correlationIds) {
            try {
                Optional<Boolean> reset = manager.resetProcess(correlationId);
                assertTrue(reset.isPresent());
                assertEquals(Boolean.TRUE, reset.get());
            }
            catch (MuProcessException expected) {
                assertTrue(expected.getMessage().startsWith("Failed to reset process"));
            }
        }

        for (String correlationId : correlationIds) {
            try {
                Optional<Boolean> reset = manager.resetProcess(correlationId);
                assertTrue(!reset.isPresent());
            }
            catch (MuProcessException expected) {
                assertTrue(expected.getMessage().startsWith("Failed to reset process"));
            }
        }
    }

    @Test
    public void testReuseCorrelationIdAfterReset() throws MuProcessException {
        String correlationId = UUID.randomUUID().toString();
        MuProcess process = manager.newProcess(correlationId);
        MuNativeActivityParameters parameters = new MuNativeActivityParameters();
        process.execute(c -> true, new BackwardSuccess(), parameters);
        try {
            process.execute(c -> false, new BackwardSuccess(), parameters);
            fail("Expected forward failure to trigger compensation");
        }
        catch (MuProcessForwardBehaviourException expected) {
            // Expected: compensated.
        }

        try {
            Optional<Boolean> reset = manager.resetProcess(correlationId);
            assertTrue(reset.isPresent());
            assertEquals(Boolean.TRUE, reset.get());
        }
        catch (MuProcessException expected) {
            assertTrue(expected.getMessage().startsWith("Failed to reset process"));
        }

        MuProcess restarted = manager.newProcess(correlationId);
        restarted.execute(c -> true, new BackwardSuccess(), parameters);
        restarted.finished();

        Optional<MuProcessState> state = manager.getProcessState(correlationId);
        assertTrue(state.isPresent());
        assertEquals(MuProcessState.SUCCESSFUL, state.get());
    }

    @Test
    public void testProcessDetailsForSuccessfulProcess() throws MuProcessException {
        String correlationId = UUID.randomUUID().toString();
        MuProcess process = manager.newProcess(correlationId);
        MuNativeActivityParameters parameters = new MuNativeActivityParameters();

        process.execute(c -> true, new BackwardSuccess(), parameters);
        process.execute(c -> true, new BackwardSuccess(), parameters);
        process.finished();

        Optional<MuProcessDetails> details = manager.getProcessDetails(correlationId);
        assertTrue(details.isPresent());
        assertEquals(MuProcessState.SUCCESSFUL, details.get().getState());
        assertTrue(details.get().getActivityDetails().isEmpty());
    }

    public static class BackwardSuccess implements MuBackwardBehaviour {
        @Override
        public boolean backward(MuBackwardActivityContext context) {
            return true;
        }
    }

    public static class BackwardFail implements MuBackwardBehaviour {
        @Override
        public boolean backward(MuBackwardActivityContext context) {
            return false;
        }
    }

    public static class BackwardSuccessCounter implements MuBackwardBehaviour {
        static final AtomicInteger CALLS = new AtomicInteger();

        static void reset() {
            CALLS.set(0);
        }

        @Override
        public boolean backward(MuBackwardActivityContext context) {
            CALLS.incrementAndGet();
            return true;
        }
    }

    public static class BackwardFailCounter implements MuBackwardBehaviour {
        static final AtomicInteger CALLS = new AtomicInteger();

        static void reset() {
            CALLS.set(0);
        }

        @Override
        public boolean backward(MuBackwardActivityContext context) {
            CALLS.incrementAndGet();
            return true;
        }
    }
}
