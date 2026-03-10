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

import org.gautelis.muprocessmanager.payload.MuForeignActivityParameters;
import org.gautelis.muprocessmanager.payload.MuForeignProcessResult;
import org.gautelis.muprocessmanager.payload.MuNativeActivityParameters;
import org.gautelis.muprocessmanager.payload.MuNativeProcessResult;
import org.junit.Test;

import javax.sql.DataSource;
import java.io.File;
import java.io.FileNotFoundException;
import java.util.Optional;
import java.util.Properties;
import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class MuProcessPayloadAndFactoryTest extends AbstractMuProcessManagerTest {
    @Test
    public void testNativePayloadRoundTrip() {
        MuNativeActivityParameters parameters = new MuNativeActivityParameters();
        parameters.put("name", "alpha");
        parameters.put("flag", true);
        parameters.put("ratio", 1.5d);

        MuNativeActivityParameters restored = MuNativeActivityParameters.fromReader(parameters.toReader());
        assertEquals("alpha", restored.get("name"));
        assertEquals(true, restored.get("flag"));
        assertEquals(1.5d, (Double) restored.get("ratio"), 0.0001d);

        MuNativeProcessResult result = new MuNativeProcessResult();
        result.add("value");
        result.add(2.0d);

        MuNativeProcessResult restoredResult = MuNativeProcessResult.fromReader(result.toReader());
        assertEquals("value", restoredResult.get(0));
        assertEquals(2.0d, (Double) restoredResult.get(1), 0.0001d);
    }

    @Test
    public void testForeignPayloadRoundTrip() {
        String payload = "{\"key\":\"value\"}";
        MuForeignActivityParameters parameters = new MuForeignActivityParameters(payload);
        MuForeignActivityParameters restored = MuForeignActivityParameters.fromReader(parameters.toReader());
        assertEquals(payload, restored.toJson());

        String resultsPayload = "[\"{\\\"a\\\":1}\",\"{\\\"b\\\":\\\"x\\\"}\"]";
        MuForeignProcessResult restoredResult = MuForeignProcessResult.fromReader(new java.io.StringReader(resultsPayload));
        assertEquals("{\"a\":1}", restoredResult.get(0));
        assertEquals("{\"b\":\"x\"}", restoredResult.get(1));
        assertEquals("[{\"a\":1},{\"b\":\"x\"}]", restoredResult.toJson());
    }

    @Test
    public void testForeignProcessDataFlowPersistsForeignResult() throws Exception {
        String dbName = uniqueDbName("mu_process_manager_foreign_");
        DataSource dataSource = MuProcessManagerFactory.getDefaultDataSource(dbName);
        MuProcessManagerFactory.prepareInternalDatabase(dataSource);
        Properties sqlStatements = MuProcessManagerFactory.getDefaultSqlStatements();

        MuProcessManagementPolicy policy = policy()
                .assumeNativeProcessDataFlow(false)
                .build();

        MuProcessManager foreignManager = MuProcessManagerFactory.getManager(dataSource, sqlStatements, policy);
        String correlationId = UUID.randomUUID().toString();
        MuProcess process = foreignManager.newProcess(correlationId);
        MuForeignActivityParameters parameters = new MuForeignActivityParameters("{\"name\":\"alpha\"}");

        process.execute(
                c -> {
                    ((MuForeignProcessResult) c.getResult()).add("{\"status\":\"ok\"}");
                    return true;
                },
                new BackwardSuccess(),
                parameters
        );
        process.finished();

        Optional<MuProcessResult> result = foreignManager.getProcessResult(correlationId);
        assertTrue(result.isPresent());
        assertTrue(result.get() instanceof MuForeignProcessResult);
        assertEquals("{\"status\":\"ok\"}", ((MuForeignProcessResult) result.get()).get(0));
        assertEquals("[{\"status\":\"ok\"}]", ((MuForeignProcessResult) result.get()).toJson());
    }

    @Test
    public void testFactoryConfigLoadErrors() throws Exception {
        File missing = new File("does-not-exist-" + UUID.randomUUID().toString() + ".xml");

        try {
            MuProcessManagerFactory.getDatabaseConfiguration(missing);
            fail("Expected missing file to throw");
        } catch (FileNotFoundException expected) {
        }

        try {
            MuProcessManagerFactory.getSqlStatements(missing);
            fail("Expected missing file to throw");
        } catch (FileNotFoundException expected) {
        }

        try {
            MuProcessManagerFactory.getManagementPolicy(missing);
            fail("Expected missing file to throw");
        } catch (FileNotFoundException expected) {
        }

        try {
            MuProcessManagerFactory.getSqlStatements(MuSynchronousManagerImpl.class, "no-such-statements.xml");
            fail("Expected unknown resource to throw");
        } catch (IllegalArgumentException expected) {
        }

        try {
            MuProcessManagerFactory.getManagementPolicy(MuSynchronousManagerImpl.class, "no-such-policy.xml");
            fail("Expected unknown resource to throw");
        } catch (IllegalArgumentException expected) {
        }
    }
}
