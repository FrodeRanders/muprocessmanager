A micro-process manager based on the Saga pattern.
==================================================

## Description of content
This library implements a drop-in micro-process manager, utilizing the Saga pattern. Out of the box,
a local embedded Derby database is used to persist processes and process activities; if the database does not 
exist, the database and tables are created automatically &#8212; trivializing development use.

In a non-development scenario, the backing database may be stored in the application database or in
a separate database.

A key concern has been to provide a relatively simple abstraction over the Saga pattern. This library
hides details from the utilizing application.

A key insight from developing this library is that the hard problem is reasoning around the fail states.
The Saga pattern only offer so much and in the end you need to understand how the fail states affect your
business application. Of course you need to understand that in a globally (distributed) transaction
environment as well, but that environment makes it easier to reason about. 

Available from Maven Central as:
```
<dependency>
    <groupId>org.gautelis</groupId>
    <artifactId>muprocessmanager</artifactId>
    <version>1.4</version>
</dependency>

```

## Background
[Hector Garcia-Molina and Kenneth Salem presented an article in 1987](http://doi.acm.org/10.1145/38713.38742)
describing a means to tackle long lived transactions, a situation where it is not 
feasible to model the transaction using the mechanisms provided by a backing database 
or the typical transaction manager.

Lately this pattern, aptly dubbed Saga, has become popular when implementing [micro-services](http://microservices.io/patterns/microservices.html).
Let us denote such micro-services that implement a process consisting of a series of individual activities, 
thus needing transactions in one form or another, a micro-process.  

To be clear, the situation we are considering here, is a series of activities in a process flow where the process as 
a whole should either succeed or fail in the [ACID](https://en.wikipedia.org/wiki/ACID) sense. Additionally, we will
consider invoking distributed services, in an environment where we do not want to use a transaction manager and where
a local database transaction cannot guarantee ACID characteristics for the process.

Having a transaction manager, the individual activities could participate in a global (distributed) transaction, having 
local transactions that individually participates in, say, the [two-phace-commit (2PC)](https://en.wikipedia.org/wiki/Two-phase_commit_protocol) 
consensus-protocol on top of the individual transactions.

![Image](doc/figure1.png?raw=true)
 
Without a transaction manager and without participating in a global transaction, is it even possible to guarantee 
ACID characteristics? The answer is, in short, no. On the other hand, if it is possible to waive some of the 
constraints of the ACID characteristics, it could be possible. 

If we could, for instance, trade _consistency_ with _eventual consistency_ or allow intermediate changes to the 
database to be visible to the outside, the Saga article lays out a pattern for implementing micro-services that does
not demand global transactions. We are making a trade here in order to drop some demands on a runtime environment. 

What the Saga pattern effectively does, is making compensational behaviour explicit in the software model, by pairing
any local transaction with the corresponding compensation and stipulating how they should behave. Compensational code 
is not new, but the Saga pattern makes it easier to reason around the failure states of the micro-process.

## A practical Saga
This software offering implements and hides the details of executing forward actions (along the "happy path") 
and reverting to backward actions in the form of compensations if the forward actions fail. 

![Image](doc/figure2.png?raw=true)
![Image](doc/figure3.png?raw=true)

The individual compensations are pushed on a persistent "stack" (logged to persistent store) ahead of attempting to
execute the local transaction. If any such local transaction fails, the "forward motion" in the process stops and the 
&#956;processmanager reverts to pop'ing compensations from the stack &#8212; executing them in a "backwards motion".

![Image](doc/figure4.png?raw=true)

The compensation is normally done synchronously in the process and an exception is thrown that both describes the problem
as well as interrupts the micro-process. The machinery around the &#956;processmanager is kind of boring (as it 
should be :) while the emphasis has to be on the internal failure states. 

If the process fails, i.e. the process thread dies before the compensations are executed completely, 
the &#956;processmanager runs recover activities in the background picking up aborted processes from the persistent 
store and executing the compensations. Eventually individual processes may remain in a partly recovered state, 
which will need some kind of external action.

![Image](src/main/java/org/gautelis/muprocessmanager/doc-files/microprocess-manager-states-description.png?raw=true)

Processes are identified through a [correlation ID](https://blog.rapid7.com/2016/12/23/the-value-of-correlation-ids/).

## Index
Recover functionality are found in [`MuProcessManager::recover`](src/main/java/org/gautelis/muprocessmanager/MuProcessManager.java).

State transitions related to forward activities are found in [`MuProcess::execute`](src/main/java/org/gautelis/muprocessmanager/MuProcess.java) and backwards activities in 
[`MuProcess::compensate`](src/main/java/org/gautelis/muprocessmanager/MuProcess.java).

All reading and writing from database (the persistent compensation log) is implemented in [`MuPersistentLog`](src/main/java/org/gautelis/muprocessmanager/MuPersistentLog.java).

Example activities are found in the test-package.

## Example
How to prepare the &#956;processmanager:
```
// Prepare process manager
MuProcessManager mngr;
try {
    mngr = MuProcessManager.getManager();
    mngr.start();
}
catch (Exception e) {
    String info = "Failed to prepare process manager: " + e.getMessage();
    throw new Exception(info, e);
}
```

How to instantiate and run a process:
```
// A correlation ID identifying this process
String correlationId = UUID.randomUUID().toString();
```

```
// This implements a micro-process, consisting of a series
// of individual activities.
MuProcess process = null;
try {
    MuActivityParameters parameters = new MuActivityParameters();

    process = mngr.newProcess(correlationId);
    
    parameters.put("arg1", "param1");
    process.execute(new FirstActivity(), parameters);
    
    parameters.put("arg2", 42);
    process.execute(new SecondActivity(), parameters);
    
    parameters.put("arg3", true);
    process.execute(new ThirdActivity(), parameters);
    
    parameters.put("arg4", 22 / 7.0);
    process.execute(new FourthActivity(), parameters);
    
    process.finished();   
    
} catch (MuProcessForwardBehaviourException mpfae) {
    // Forward activity failed, but compensations were successful
    String info = "No success, but managed to compensate: " + mpfae.getMessage();
    log.info(info, mpfae);
    
} catch (MuProcessBackwardBehaviourException mpbae) {
    // Forward activity failed and so did some compensation activities
    String info = "Process and compensation failure: " + mpbae.getMessage();
    log.warn(info, mpbae);
    
}
catch (Throwable t) {
    // Other reasons for failure not necessarily related to the activity
    if (null != process) {
        process.failed();
    }

    String info = "Process failure: " + t.getMessage();
    log.warn(info);
}
```

An individual activity from the example above (`test.FirstActivity`),
in this case a simple dummy load with probabilistic failure behaviour:
```
package test;

import org.gautelis.muprocessmanager.MuActivity;
import org.gautelis.muprocessmanager.MuActivityParameters;

public class FirstActivity implements MuActivity {

    private static final double forwardFailureProbability = 0.01; // 1%
    private static final double backwardFailureProbability = 0.01; // 1%

    public FirstActivity() {}

    /**
     * Implements a local "transaction", the forward motion action of this
     * activity. 
     */
    @Override
    public boolean forward(MuActivityParameters args, MuProcessResult result) {
        return !(Math.random() < forwardFailureProbability);
    }

    /**
     * Implements the corresponding compensation, the backwards motion action
     * of this activity.
     */
    @Override
    public boolean backward(MuActivityParameters args, Optional<MuActivityState> preState) {
        return !(Math.random() < backwardFailureProbability);
    }
    
}
```

Example logging (from test cases with probabilistic failure rates) from the background
tasks of the `MuProcessManager`:
```
...
[statistics] {8 PROGRESSING} {136 SUCCESSFUL} {155 COMPENSATED} {9 COMPENSATION_FAILED} {308 in total}
[recover]    {8 attempted compensations from COMPENSATION_FAILED} {308 observed in total}
[statistics] {2 NEW} {6 PROGRESSING} {1741 SUCCESSFUL} {2190 COMPENSATED} {180 COMPENSATION_FAILED} {4119 in total}
[statistics] {3 NEW} {8 PROGRESSING} {3745 SUCCESSFUL} {4843 COMPENSATED} {394 COMPENSATION_FAILED} {8993 in total}
[recover] [  {79 attempted compensations from COMPENSATION_FAILED} {9008 observed in total}
[statistics] {2 NEW} {7 PROGRESSING} {6060 SUCCESSFUL} {8105 COMPENSATED} {226 COMPENSATION_FAILED} {14400 in total}
[statistics] {12 PROGRESSING} {8662 SUCCESSFUL} {11246 COMPENSATED} {495 COMPENSATION_FAILED} {20415 in total}
[recover]    {222 removed from SUCCESSFUL} {276 removed from COMPENSATED} {496 attempted compensations from COMPENSATION_FAILED} {20416 observed in total}
[statistics] {1 NEW} {7 PROGRESSING} {10813 SUCCESSFUL} {14523 COMPENSATED} {282 COMPENSATION_FAILED} {25626 in total}
[statistics] {1 NEW} {7 PROGRESSING} {13432 SUCCESSFUL} {17805 COMPENSATED} {547 COMPENSATION_FAILED} {31792 in total}
...
```
