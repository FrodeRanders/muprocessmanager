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
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class MuVolatileProcessTest {
    @Test
    public void testSuccessfulForwardDoesNotCompensate() throws MuProcessException {
        MuVolatileProcess process = new MuVolatileProcess("corr-1", false, true);
        MuNativeActivityParameters parameters = new MuNativeActivityParameters();
        AtomicInteger backwardCalls = new AtomicInteger();

        process.execute(
                c -> true,
                c -> {
                    backwardCalls.incrementAndGet();
                    return true;
                },
                parameters
        );

        assertEquals(0, backwardCalls.get());
        assertTrue(process.getResult() instanceof MuNativeProcessResult);
    }

    @Test
    public void testForwardFailureCompensatesInReverseOrder() throws MuProcessException {
        MuVolatileProcess process = new MuVolatileProcess("corr-2", false, true);
        MuNativeActivityParameters parameters = new MuNativeActivityParameters();
        List<String> calls = new ArrayList<>();

        process.execute(
                c -> {
                    calls.add("forward1");
                    return true;
                },
                c -> {
                    calls.add("backward1");
                    return true;
                },
                parameters
        );

        try {
            process.execute(
                    c -> {
                        calls.add("forward2");
                        return false;
                    },
                    c -> {
                        calls.add("backward2");
                        return true;
                    },
                    parameters
            );
            fail("Expected forward failure to trigger compensation");
        }
        catch (MuProcessForwardBehaviourException expected) {
            // Expected: forward failed and compensation succeeded.
        }

        assertEquals(
                Arrays.asList("forward1", "forward2", "backward2", "backward1"),
                calls
        );
    }

    @Test
    public void testCompensationFailureThrowsBackwardException() throws MuProcessException {
        MuVolatileProcess process = new MuVolatileProcess("corr-3", false, true);
        MuNativeActivityParameters parameters = new MuNativeActivityParameters();

        process.execute(
                c -> true,
                c -> true,
                parameters
        );

        try {
            process.execute(
                    c -> false,
                    c -> false,
                    parameters
            );
            fail("Expected compensation failure to throw");
        }
        catch (MuProcessBackwardBehaviourException expected) {
            // Expected: compensation failed with acceptCompensationFailure=false.
        }
    }
}
