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
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class MuProcessPersistentCoreTest extends AbstractMuProcessManagerTest {
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
        assertEquals("ok", ((MuNativeProcessResult) result.get()).get(0));
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
        } catch (MuProcessForwardBehaviourException expected) {
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
        } catch (MuProcessBackwardBehaviourException expected) {
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
        } catch (MuProcessBackwardBehaviourException expected) {
        }

        Optional<Boolean> reset = manager.resetProcess(correlationId);
        assertTrue(reset.isPresent());
        assertEquals(Boolean.TRUE, reset.get());
        assertTrue(!manager.getProcessState(correlationId).isPresent());
    }

    @Test
    public void testForwardOnlyFailureMarksCompensated() throws MuProcessException {
        String correlationId = UUID.randomUUID().toString();
        MuProcess process = manager.newProcess(correlationId);
        MuNativeActivityParameters parameters = new MuNativeActivityParameters();

        try {
            process.execute(c -> false, parameters);
            fail("Expected forward-only failure to throw");
        } catch (MuProcessForwardBehaviourException expected) {
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
        assertTrue(!manager.getProcessResult(correlationId).isPresent());
    }

    @Test
    public void testGetResultUnavailableWhenNotSuccessful() throws MuProcessException {
        String correlationId = UUID.randomUUID().toString();
        MuProcess process = manager.newProcess(correlationId);

        try {
            process.execute(c -> false, new MuNativeActivityParameters());
            fail("Expected forward-only failure to throw");
        } catch (MuProcessForwardBehaviourException expected) {
        }

        try {
            manager.getProcessResult(correlationId);
            fail("Expected result to be unavailable for non-successful process");
        } catch (MuProcessResultsUnavailable expected) {
        }
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
        } catch (MuProcessException expected) {
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
        } catch (MuProcessException expected) {
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
        } catch (MuProcessForwardBehaviourException expected) {
            assertTrue(expected.getMessage().contains("Forward activity failed"));
        }

        Optional<MuProcessState> state = manager.getProcessState(correlationId);
        assertTrue(state.isPresent());
        assertEquals(MuProcessState.SUCCESSFUL, state.get());
    }

    @Test
    public void testLambdaBackwardBehaviourIsRejectedBeforePersistence() throws MuProcessException {
        String correlationId = UUID.randomUUID().toString();
        MuProcess process = manager.newProcess(correlationId);

        try {
            process.execute(c -> true, c -> true, new MuNativeActivityParameters());
            fail("Expected lambda backward behaviour to be rejected");
        } catch (MuProcessException expected) {
            assertTrue(expected.getMessage().contains("Backward behaviour can not be a lambda"));
        }

        assertTrue(!manager.getProcessState(correlationId).isPresent());
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
                    process.execute(c -> true, new MuNativeActivityParameters());
                    process.finished();
                } catch (MuProcessException ignore) {
                } finally {
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
            } catch (MuProcessForwardBehaviourException expected) {
            }
        }

        for (String correlationId : correlationIds) {
            try {
                Optional<Boolean> reset = manager.resetProcess(correlationId);
                assertTrue(reset.isPresent());
                assertEquals(Boolean.TRUE, reset.get());
            } catch (MuProcessException expected) {
                assertTrue(expected.getMessage().startsWith("Failed to reset process"));
            }
        }

        for (String correlationId : correlationIds) {
            try {
                Optional<Boolean> reset = manager.resetProcess(correlationId);
                assertTrue(!reset.isPresent());
            } catch (MuProcessException expected) {
                assertTrue(expected.getMessage().startsWith("Failed to reset process"));
            }
        }
    }

    @Test
    public void testReuseCorrelationIdAfterReset() throws MuProcessException {
        String correlationId = UUID.randomUUID().toString();
        MuNativeActivityParameters parameters = new MuNativeActivityParameters();
        MuProcess process = manager.newProcess(correlationId);
        process.execute(c -> true, new BackwardSuccess(), parameters);
        try {
            process.execute(c -> false, new BackwardSuccess(), parameters);
            fail("Expected forward failure to trigger compensation");
        } catch (MuProcessForwardBehaviourException expected) {
        }

        try {
            Optional<Boolean> reset = manager.resetProcess(correlationId);
            assertTrue(reset.isPresent());
            assertEquals(Boolean.TRUE, reset.get());
        } catch (MuProcessException expected) {
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
        MuNativeActivityParameters parameters = new MuNativeActivityParameters();
        MuProcess process = manager.newProcess(correlationId);

        process.execute(c -> true, new BackwardSuccess(), parameters);
        process.execute(c -> true, new BackwardSuccess(), parameters);
        process.finished();

        Optional<MuProcessDetails> details = manager.getProcessDetails(correlationId);
        assertTrue(details.isPresent());
        assertEquals(MuProcessState.SUCCESSFUL, details.get().getState());
        assertTrue(details.get().getActivityDetails().isEmpty());
    }
}
