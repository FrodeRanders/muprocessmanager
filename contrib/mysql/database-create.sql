-- -------------------------------------------------------------
-- Copyright (C) 2017-2021 Frode Randers
-- All rights reserved
--
-- Licensed under the Apache License, Version 2.0 (the "License");
-- you may not use this file except in compliance with the License.
-- You may obtain a copy of the License at
--
--    http://www.apache.org/licenses/LICENSE-2.0
--
-- Unless required by applicable law or agreed to in writing, software
-- distributed under the License is distributed on an "AS IS" BASIS,
-- WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
-- See the License for the specific language governing permissions and
-- limitations under the License.
-- -------------------------------------------------------------

-- -------------------------------------------------------------
-- Database schema: MySQL
--
-- Remember to grant privileges to the specific database user:
--
--    GRANT ALL PRIVILEGES ON muproc.* TO 'muproc'@'remote-ip' identified by 'xyz';
--
-- -------------------------------------------------------------

-- -------------------------------------------------------------
-- Processes
--
CREATE TABLE mu_process (
  process_id SERIAL PRIMARY KEY,

  correlation_id VARCHAR(255) NOT NULL, -- for now

  state INTEGER NOT NULL DEFAULT 0, -- 0=new, 1=progressing, 2=successful, 3=compensated, 4=compensation-failed, 5=abandoned
  accept_failure BOOLEAN NOT NULL DEFAULT true,

  result TEXT DEFAULT NULL,

  created TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  modified TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE UNIQUE INDEX mu_process_corrid_ix ON mu_process ( correlation_id );

-- -------------------------------------------------------------
--
--
CREATE TABLE mu_process_step (
  process_id BIGINT UNSIGNED NOT NULL, -- to match SERIAL in mu_process
  step_id INTEGER NOT NULL, -- step id
  PRIMARY KEY (process_id, step_id),

  CONSTRAINT mu_p_s_process_ex
    FOREIGN KEY (process_id) REFERENCES mu_process(process_id),

  class_name VARCHAR(255) NOT NULL,  -- qualified class name must fit
  method_name VARCHAR(255) NOT NULL, -- method name must fit
  activity_params TEXT NOT NULL,
  orchestr_params TEXT DEFAULT NULL,
  previous_state TEXT DEFAULT NULL,

  compensate_if_failure BOOLEAN NOT NULL DEFAULT false,
  transaction_successful BOOLEAN DEFAULT NULL,

  retries INTEGER NOT NULL DEFAULT 0,
  created TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  modified TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

