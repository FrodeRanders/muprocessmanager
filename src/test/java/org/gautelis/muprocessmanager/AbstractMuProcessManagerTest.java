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

import org.gautelis.vopn.queue.WorkQueue;
import org.junit.After;
import org.junit.Before;

import javax.sql.DataSource;
import java.util.Optional;
import java.util.Properties;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.fail;

abstract class AbstractMuProcessManagerTest {
    protected static final long DEFAULT_AWAIT_TIMEOUT_MILLIS = 5000L;
    protected static final long DEFAULT_AWAIT_POLL_MILLIS = 50L;
    protected MuProcessManager manager;

    @Before
    public void setUp() throws MuProcessException {
        manager = newDefaultManager("mu_process_manager_test_");
        manager.start();
    }

    @After
    public void tearDown() {
        if (manager != null) {
            manager.stop();
        }
    }

    protected static MuProcessManager newDefaultManager(String prefix) throws MuProcessException {
        DataSource dataSource = MuProcessManagerFactory.getDefaultDataSource(uniqueDbName(prefix));
        MuProcessManagerFactory.prepareInternalDatabase(dataSource);
        return MuProcessManagerFactory.getManager(dataSource);
    }

    protected static MuSynchronousManagerImpl newSynchronousManager(String prefix, MuProcessManagementPolicy policy) throws MuProcessException {
        DataSource dataSource = MuProcessManagerFactory.getDefaultDataSource(uniqueDbName(prefix));
        MuProcessManagerFactory.prepareInternalDatabase(dataSource);
        Properties sqlStatements = MuProcessManagerFactory.getDefaultSqlStatements();
        return new MuSynchronousManagerImpl(dataSource, sqlStatements, policy);
    }

    protected static MuAsynchronousManagerImpl newAsynchronousManager(String prefix, MuProcessManagementPolicy policy) throws MuProcessException {
        DataSource dataSource = MuProcessManagerFactory.getDefaultDataSource(uniqueDbName(prefix));
        MuProcessManagerFactory.prepareInternalDatabase(dataSource);
        Properties sqlStatements = MuProcessManagerFactory.getDefaultSqlStatements();
        return new MuAsynchronousManagerImpl(dataSource, sqlStatements, policy);
    }

    protected static ManagedPair newManagedPair(String prefix, MuProcessManagementPolicy policy) throws MuProcessException {
        DataSource dataSource = MuProcessManagerFactory.getDefaultDataSource(uniqueDbName(prefix));
        MuProcessManagerFactory.prepareInternalDatabase(dataSource);
        Properties sqlStatements = MuProcessManagerFactory.getDefaultSqlStatements();
        return new ManagedPair(
                new MuSynchronousManagerImpl(dataSource, sqlStatements, policy),
                new MuAsynchronousManagerImpl(dataSource, sqlStatements, policy),
                dataSource,
                sqlStatements
        );
    }

    protected static ManagedPair newManagedPair(String prefix, MuProcessManagementPolicy policy, WorkQueue recoverWorkQueue) throws MuProcessException {
        DataSource dataSource = MuProcessManagerFactory.getDefaultDataSource(uniqueDbName(prefix));
        MuProcessManagerFactory.prepareInternalDatabase(dataSource);
        Properties sqlStatements = MuProcessManagerFactory.getDefaultSqlStatements();
        return new ManagedPair(
                new MuSynchronousManagerImpl(dataSource, sqlStatements, policy),
                new MuAsynchronousManagerImpl(dataSource, sqlStatements, policy, recoverWorkQueue),
                dataSource,
                sqlStatements
        );
    }

    protected static String uniqueDbName(String prefix) {
        return prefix + UUID.randomUUID().toString().replace("-", "");
    }

    protected static TestPolicyBuilder policy() {
        return new TestPolicyBuilder();
    }

    protected static int maxRetries(MuProcessDetails details) {
        int max = 0;
        for (MuProcessDetails.MuActivityDetails activity : details.getActivityDetails()) {
            if (activity.getRetries() > max) {
                max = activity.getRetries();
            }
        }
        return max;
    }

    protected static MuProcessState awaitProcessState(
            MuSynchronousManagerImpl manager, String correlationId, MuProcessState expectedState, long timeoutMillis
    ) throws Exception {
        return awaitValue(
                "process " + correlationId + " to reach state " + expectedState,
                () -> {
                    Optional<MuProcessState> state = manager.getProcessState(correlationId);
                    return state.orElse(null);
                },
                state -> state == expectedState,
                timeoutMillis
        );
    }

    protected static boolean awaitProcessMissing(MuSynchronousManagerImpl manager, String correlationId, long timeoutMillis) throws Exception {
        awaitCondition(
                "process " + correlationId + " to be removed",
                () -> !manager.getProcessState(correlationId).isPresent(),
                timeoutMillis
        );
        return true;
    }

    protected static void awaitCondition(String description, CheckedBooleanSupplier supplier, long timeoutMillis) throws Exception {
        awaitValue(description, () -> supplier.getAsBoolean(), value -> value, timeoutMillis);
    }

    protected static <T> T awaitValue(
            String description,
            CheckedSupplier<T> supplier,
            Predicate<T> done,
            long timeoutMillis
    ) throws Exception {
        T lastValue = null;
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            lastValue = supplier.get();
            if (done.test(lastValue)) {
                return lastValue;
            }
            Thread.sleep(DEFAULT_AWAIT_POLL_MILLIS);
        }
        fail("Timed out waiting for " + description + "; last observed value: " + String.valueOf(lastValue));
        return lastValue;
    }

    protected static final class ManagedPair {
        final MuSynchronousManagerImpl syncManager;
        final MuAsynchronousManagerImpl asyncManager;
        final DataSource dataSource;
        final Properties sqlStatements;

        ManagedPair(MuSynchronousManagerImpl syncManager, MuAsynchronousManagerImpl asyncManager, DataSource dataSource, Properties sqlStatements) {
            this.syncManager = syncManager;
            this.asyncManager = asyncManager;
            this.dataSource = dataSource;
            this.sqlStatements = sqlStatements;
        }
    }

    @FunctionalInterface
    protected interface CheckedSupplier<T> {
        T get() throws Exception;
    }

    @FunctionalInterface
    protected interface CheckedBooleanSupplier {
        boolean getAsBoolean() throws Exception;
    }

    protected static final class TestPolicyBuilder {
        private int minutesToTrackProcess = 5;
        private int minutesBeforeAssumingProcessStuck = 5;
        private int secondsBetweenRecoveryAttempts = 30;
        private int secondsBetweenRecompensationAttempts = 30;
        private int secondsBetweenLoggingStatistics = 60;
        private boolean acceptCompensationFailure = true;
        private boolean onlyCompensateIfTransactionWasSuccessful = false;
        private int numberOfRecoveryThreads = 1;
        private boolean assumeNativeProcessDataFlow = true;

        TestPolicyBuilder minutesToTrackProcess(int value) {
            minutesToTrackProcess = value;
            return this;
        }

        TestPolicyBuilder minutesBeforeAssumingProcessStuck(int value) {
            minutesBeforeAssumingProcessStuck = value;
            return this;
        }

        TestPolicyBuilder secondsBetweenRecoveryAttempts(int value) {
            secondsBetweenRecoveryAttempts = value;
            return this;
        }

        TestPolicyBuilder secondsBetweenRecompensationAttempts(int value) {
            secondsBetweenRecompensationAttempts = value;
            return this;
        }

        TestPolicyBuilder secondsBetweenLoggingStatistics(int value) {
            secondsBetweenLoggingStatistics = value;
            return this;
        }

        TestPolicyBuilder acceptCompensationFailure(boolean value) {
            acceptCompensationFailure = value;
            return this;
        }

        TestPolicyBuilder onlyCompensateIfTransactionWasSuccessful(boolean value) {
            onlyCompensateIfTransactionWasSuccessful = value;
            return this;
        }

        TestPolicyBuilder numberOfRecoveryThreads(int value) {
            numberOfRecoveryThreads = value;
            return this;
        }

        TestPolicyBuilder assumeNativeProcessDataFlow(boolean value) {
            assumeNativeProcessDataFlow = value;
            return this;
        }

        MuProcessManagementPolicy build() {
            return new MuProcessManagementPolicy() {
                @Override
                public int minutesToTrackProcess() {
                    return minutesToTrackProcess;
                }

                @Override
                public int minutesBeforeAssumingProcessStuck() {
                    return minutesBeforeAssumingProcessStuck;
                }

                @Override
                public int secondsBetweenRecoveryAttempts() {
                    return secondsBetweenRecoveryAttempts;
                }

                @Override
                public int secondsBetweenRecompensationAttempts() {
                    return secondsBetweenRecompensationAttempts;
                }

                @Override
                public int secondsBetweenLoggingStatistics() {
                    return secondsBetweenLoggingStatistics;
                }

                @Override
                public boolean acceptCompensationFailure() {
                    return acceptCompensationFailure;
                }

                @Override
                public boolean onlyCompensateIfTransactionWasSuccessful() {
                    return onlyCompensateIfTransactionWasSuccessful;
                }

                @Override
                public int numberOfRecoveryThreads() {
                    return numberOfRecoveryThreads;
                }

                @Override
                public boolean assumeNativeProcessDataFlow() {
                    return assumeNativeProcessDataFlow;
                }
            };
        }
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
