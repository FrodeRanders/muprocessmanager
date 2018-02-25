/*
 * Copyright (C) 2017 Frode Randers
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
package test;

import org.gautelis.muprocessmanager.*;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.UUID;

/**
 */
public class StandaloneApp {
    private static final Logger log = LogManager.getLogger(StandaloneApp.class);

    public static void main(String... arg) {

        MuProcessManager mngr = null;
        try {
            mngr = MuProcessManager.getManager();
            mngr.start();

            String correlationId = UUID.randomUUID().toString();

            MuProcess process = null;
            try {
                process = mngr.newProcess(correlationId);

                MuActivityParameters parameters = new MuActivityParameters();
                parameters.put("arg1", "param1");
                process.execute(new FirstActivity(), parameters);

                parameters.put("arg2", 42);
                process.execute(
                        (p) -> !(Math.random() < 0.0001),
                        new SecondActivityCompensation(),
                        parameters
                );

                parameters.put("arg3", true);
                process.execute(new ThirdActivity(), parameters);

                parameters.put("arg4", 22 / 7.0);
                process.execute(new FourthActivity(), parameters);

                process.finished();

            } catch (MuProcessBackwardBehaviourException mpbae) {
                // Forward activity failed and so did some compensation activities
                String info = "Process and compensation failure: " + mpbae.getMessage();
                log.warn(info);

            } catch (MuProcessForwardBehaviourException mpfae) {
                // Forward activity failed, but compensations were successful
                String info = "No success: " + mpfae.getMessage();
                log.trace(info);

            } catch (Throwable t) {
                // Other reasons for failure not necessarily related to the activity
                if (null != process) {
                    process.failed();
                }

                String info = "Process failure: " + t.getMessage();
                log.warn(info);
            }
        }
        catch (MuProcessException mpe) {
            mpe.printStackTrace();
        }
        finally {
            if (null != mngr) {
                mngr.stop();
            }
        }
    }
}
