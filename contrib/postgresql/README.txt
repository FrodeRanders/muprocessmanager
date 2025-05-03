You can either choose to run 'setup-for-test.py' or manually set up the environment.

---8<--------------------------------------------------------------------

// Automated //

➜   python contrib/postgresql/setup-for-test.py
Pulling the latest 'postgres' image from Docker Hub...
Using default tag: latest
latest: Pulling from library/postgres
Digest: sha256:304ab813518754228f9f792f79d6da36359b82d8ecf418096c636725f8c930ad
Status: Image is up to date for postgres:latest
docker.io/library/postgres:latest
Image pulled successfully.

Removing existing container named 'muproc' (if any)...
Removed old container 'muproc'.

Starting new container 'muproc' on port 1402 ...
f94c373ef73ca468a857bfa3b96e0971289df720bf04d250fde9ab597018baec
Container started.

Waiting 10 seconds for the database to initialize...
Proceeding...

Entering container to run psql commands...
Commands executed. Exiting psql.
All SQL files have been executed.

Setup completed.

You can now connect using something like:
  psql -h localhost -p 1402 -U postgres
using the superuser password you specified.

➜

---8<--------------------------------------------------------------------

// Manual //

~ > docker pull postgres
Using default tag: latest
latest: Pulling from library/postgres
c73ab1c6897b: Pull complete 
e94aaf8397f8: Pull complete 
0853c2cf63f7: Pull complete 
487961619c6f: Pull complete 
b3fe80b87ac6: Pull complete 
4ca9a0468275: Pull complete 
b08717267268: Pull complete 
46e01a893aae: Pull complete 
6a59d7eed20d: Pull complete 
92ce89955be9: Pull complete 
72d094cdd68b: Pull complete 
551b1e46cd6f: Pull complete 
a3591a6c2a19: Pull complete 
Digest: sha256:d5787305ec0a3b9a24d0108cb5fdbb4befbd809f85639bf04aa1941138df9701
Status: Downloaded newer image for postgres:latest

~ > docker run --name muproc-postgres -e POSTGRES_PASSWORD=H0nd@666 -p 1402:5432 -d postgres 
1bd3d7c6905679b5a5fc41086768e4923c3ec54c16d971b1483533ced71c7d05

~ > docker ps -all
CONTAINER ID        IMAGE               COMMAND                  CREATED              STATUS              PORTS                    NAMES
1bd3d7c69056        postgres            "docker-entrypoint.s…"   About a minute ago   Up 58 seconds       0.0.0.0:1402->5432/tcp   muproc-postgres

~ > 

## Connect to it (for creating stuff)

~ > docker run -it --rm --link muproc-postgres:postgres postgres psql -h postgres -U postgres
Password for user postgres: 

psql (10.3 (Debian 10.3-1.pgdg90+1))
Type "help" for help.

postgres=# CREATE USER muproc WITH PASSWORD 'muproc';
CREATE ROLE
postgres=# CREATE DATABASE muproc;
CREATE DATABASE
postgres=# GRANT ALL PRIVILEGES ON DATABASE muproc TO muproc;
GRANT
postgres=# \q


~ > docker run -it --rm --link muproc-postgres:postgres postgres psql -h postgres -U muproc  
Password for user muproc: 
psql (10.3 (Debian 10.3-1.pgdg90+1))
Type "help" for help.

muproc=> CREATE TABLE mu_process (
muproc(>   process_id SERIAL,
muproc(>   PRIMARY KEY (process_id),
muproc(> 
muproc(>   correlation_id VARCHAR(255) NOT NULL, -- for now
muproc(> 
muproc(>   state INTEGER NOT NULL DEFAULT 0, -- 0=new, 1=progressing, 2=successful, 3=compensated, 4=compensation-failed, 5=abandoned
muproc(>   accept_failure BOOLEAN NOT NULL DEFAULT true,
muproc(> 
muproc(>   result TEXT DEFAULT NULL,
muproc(> 
muproc(>   created TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
muproc(>   modified TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
muproc(> );
CREATE TABLE
muproc=> CREATE UNIQUE INDEX mu_process_corrid_ix ON mu_process ( correlation_id );
CREATE INDEX
muproc=> CREATE TABLE mu_process_step (
muproc(>   process_id INTEGER NOT NULL,
muproc(>   step_id INTEGER NOT NULL, -- step id
muproc(>   PRIMARY KEY (process_id, step_id),
muproc(> 
muproc(>   CONSTRAINT mu_p_s_process_ex
muproc(>   FOREIGN KEY (process_id) REFERENCES mu_process(process_id),
muproc(> 
muproc(>   class_name VARCHAR(255) NOT NULL,  -- qualified class name must fit
muproc(>   method_name VARCHAR(255) NOT NULL, -- method name must fit
muproc(>   activity_params TEXT NOT NULL,
muproc(>   orchestr_params TEXT DEFAULT NULL,
muproc(>   previous_state TEXT DEFAULT NULL,
muproc(> 
muproc(>   retries INTEGER NOT NULL DEFAULT 0,
muproc(>   created TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
muproc(>   modified TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
muproc(> );
CREATE TABLE
muproc=> \l
                                 List of databases
   Name    |  Owner   | Encoding |  Collate   |   Ctype    |   Access privileges   
-----------+----------+----------+------------+------------+-----------------------
 muproc    | postgres | UTF8     | en_US.utf8 | en_US.utf8 | =Tc/postgres         +
           |          |          |            |            | postgres=CTc/postgres+
           |          |          |            |            | muproc=CTc/postgres
 postgres  | postgres | UTF8     | en_US.utf8 | en_US.utf8 | 
 template0 | postgres | UTF8     | en_US.utf8 | en_US.utf8 | =c/postgres          +
           |          |          |            |            | postgres=CTc/postgres
 template1 | postgres | UTF8     | en_US.utf8 | en_US.utf8 | =c/postgres          +
           |          |          |            |            | postgres=CTc/postgres
(4 rows)

muproc=> \q


## More at https://hub.docker.com/_/postgres/

