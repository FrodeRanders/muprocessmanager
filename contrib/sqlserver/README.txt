~ > docker --version
Docker version 18.03.0-ce, build 0520e24

~ > docker pull microsoft/mssql-server-linux:2017-latest
Password:
2017-latest: Pulling from microsoft/mssql-server-linux
f6fa9a861b90: Pull complete 
da7318603015: Pull complete 
6a8bd10c9278: Pull complete 
d5a40291440f: Pull complete 
bbdd8a83c0f1: Pull complete 
3a52205d40a6: Pull complete 
6192691706e8: Pull complete 
1a658a9035fb: Pull complete 
9da82922361a: Pull complete 
e239e6b571a6: Pull complete 
Digest: sha256:01c9766ba443a92019baa031c9d288bfc7efb45d5972dcc5a118caba126943ec
Status: Downloaded newer image for microsoft/mssql-server-linux:2017-latest

~ > docker run -e 'ACCEPT_EULA=Y' -e 'MSSQL_SA_PASSWORD=H0nd@666' \                
   -p 1401:1433 --name muproc \
   -d microsoft/mssql-server-linux:2017-latest
2e6c68b3cf65ac049973e9a5bb4e429692c864a3cb71c7371925bd73c212ccb2

~ > docker ps -all
CONTAINER ID        IMAGE                                      COMMAND                  CREATED             STATUS              PORTS                    NAMES
867f4b6ad633        microsoft/mssql-server-linux:2017-latest   "/opt/mssql/bin/sqls…"   3 hours ago         Up 3 hours          0.0.0.0:1401->1433/tcp   muproc

~ > docker exec -t -i muproc /bin/bash
root@867f4b6ad633:/# cd /var/opt/mssql
root@867f4b6ad633:/var/opt/mssql# ls -lR
.:
total 12
drwxr-xr-x 2 root root 4096 Apr  7 11:24 data
drwxr-xr-x 2 root root 4096 Apr  7 14:40 log
drwxr-xr-x 2 root root 4096 Apr  7 11:24 secrets

./data:
total 55168
-rw-r----- 1 root root  4653056 Apr  7 14:05 master.mdf
-rw-r----- 1 root root  2097152 Apr  7 14:40 mastlog.ldf
-rw-r----- 1 root root  8388608 Apr  7 11:24 model.mdf
-rw-r----- 1 root root  8388608 Apr  7 11:25 modellog.ldf
-rw-r----- 1 root root 15400960 Apr  7 11:25 msdbdata.mdf
-rw-r----- 1 root root   786432 Apr  7 11:25 msdblog.ldf
-rw-r----- 1 root root  8388608 Apr  7 11:24 tempdb.mdf
-rw-r----- 1 root root  8388608 Apr  7 11:24 templog.ldf

./log:
total 5064
-rw-r----- 1 root root   77824 Apr  7 11:24 HkEngineEventFile_0_131675738823070000.xel
-rw-r----- 1 root root   11610 Apr  7 14:26 errorlog
-rw-r----- 1 root root       0 Apr  7 11:24 errorlog.1
-rw-r----- 1 root root 1048576 Apr  7 14:25 log_29.trc
-rw-r----- 1 root root 1048576 Apr  7 14:30 log_30.trc
-rw-r----- 1 root root 1048576 Apr  7 14:35 log_31.trc
-rw-r----- 1 root root 1048576 Apr  7 14:40 log_32.trc
-rw-r----- 1 root root    3584 Apr  7 14:40 log_33.trc
-rw-r----- 1 root root    7356 Apr  7 11:24 sqlagent.out
-rw-r----- 1 root root     113 Apr  7 11:24 sqlagentstartup.log
-rw-r----- 1 root root  884736 Apr  7 14:42 system_health_0_131675738831750000.xel

./secrets:
total 4
-rw------- 1 root root 44 Apr  7 11:24 machine-key
root@867f4b6ad633:/var/opt/mssql# exit

~ > docker port 867f4b6ad633
1433/tcp -> 0.0.0.0:1401


-- Connected as 'sa', create a new database 'muproc'
USE master;  
GO  

CREATE DATABASE muproc  
ON   
( NAME = muproc_dat,  
    FILENAME = '/var/opt/mssql/data/muproc.mdf',  
    SIZE = 10,  
    MAXSIZE = 50,  
    FILEGROWTH = 5 )  
LOG ON  
( NAME = muproc_log,  
    FILENAME = '/var/opt/mssql/data/muproc.ldf',  
    SIZE = 5MB,  
    MAXSIZE = 25MB,  
    FILEGROWTH = 5MB );  
GO  

-- Create a login 'muproc' with password 'muproc' with default database 'muproc'
CREATE LOGIN muproc WITH PASSWORD = 'muproc', DEFAULT_DATABASE=muproc, CHECK_POLICY= OFF;
GO

USE muproc;
GO

EXEC sp_changedbowner 'muproc';
GO

-- Who owns the individual databases?
SELECT db.name AS 'Database', sp.name AS 'Owner' 
FROM sys.databases db 
  LEFT JOIN sys.server_principals sp 
    ON db.owner_sid = sp.sid 
ORDER BY db.name;

-- which produces something akin to 
+----------+--------+
| Database | Owner  |
+----------+--------+
|  master  | sa     |
|  model   | sa     |
|  msdb    | sa     |
|  muproc  | muproc |
|  tempdb  | sa     |
+----------+--------+

-- Connected as 'muproc', create the individual tables 
-- (according to create_database.sql)


