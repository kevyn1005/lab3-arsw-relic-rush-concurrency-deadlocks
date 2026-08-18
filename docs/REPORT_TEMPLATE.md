# ARSW Lab 3 - Relic Rush - Delivery Report

## Team

| Student | ID | GitHub |
|---|---|---|
| | | |
| | | |
| | | |

Repository: `https://github.com/kevyn1005/lab3-arsw-relic-rush-concurrency-deadlocks.git`

Final commit: `SHA`

## 1. Baseline observations

- Command(s) executed:
- What happened?
- Was the round invariant always preserved?
- Did the game stop unexpectedly?

Evidence:

```text
PASTE RELEVANT OUTPUT
```

## 2. Coordination analysis

Explain the responsibility of both barriers:

- `roundStart`:It ensures that no adventurer starts round N until everyone, including the coordinator, has reached that point. This prevents one thread from starting early while others are still finishing the previous round or the coordinator is still reading the previous snapshot. In this way, the barrier makes sure that all threads start the new round at the same time.
- `roundEnd`: The coordinator needs to wait until all N adventurers have completely finished their turn, including score++ and ledger.record(...), before reading scoreSum, ledgerTotal, and eventCount in printRoundSnapshot. Without this barrier, the coordinator could take the snapshot while some adventurers are still finishing their crafting. This could break the scoreSum == ledger == events invariant simply because the snapshot was taken before the round was completely finished.
- Why is `Thread.sleep(...)` not a valid replacement for a barrier?
> - There is no guarantee that everyone has finished: sleep(N) is just an estimate of how long playTurn() will take. If an adventurer takes longer, for example, because they are waiting for a station lock, the coordinator could take the snapshot before that thread finishes. This could cause invariant=BROKEN even though there is no real problem in the design.
> - It can waste time or be insufficient: if the sleep is long enough to cover the worst case, time is wasted in every round. If it is too short, the problem will happen intermittently.
> - It does not provide happens-before: without explicit synchronization between threads, there is no guarantee that the changes made by the adventurers will be visible to the coordinator after waking up. It could see outdated values even though the specified amount of time has already passed.


## 3. Thread-safety problems

| Shared state | Problem | Invariant at risk | Solution | Why this solution? |
|---|---|---|---|---|
| | | | | |
| | | | | |

## 4. Deadlock diagnosis

### 4.1 Evidence

```
PS C:\Users\Lenovo\Desktop\Uni\ARSW\Lab03> java -cp target/classes edu.eci.arsw.relicrush.app.DeadlockProbe
DEADLOCK DETECTED
- probe-A-anvil-then-furnace waiting on edu.eci.arsw.relicrush.model.ForgeStation@2626b418 owned by probe-B-furnace-then-anvil
- probe-B-furnace-then-anvil waiting on edu.eci.arsw.relicrush.model.ForgeStation@78308db1 owned by probe-A-anvil-then-furnace
```


### 4.2 Coffman conditions in Relic Rush

- Mutual exclusion:
- Hold and wait:
- No preemption:
- Circular wait:

### 4.3 Wait-for graph

Describe or add a diagram.

### 4.4 Fix

What condition did you break?

How did you preserve concurrency between independent forge operations?

## 5. Verification

| Players | Stations | Rounds | Deadlock? | Invariant result |
|---:|---:|---:|---|---|
| 8 | 6 | 50 | | |
| 32 | 8 | 100 | | |
| 128 | 8 | 100 | | |

## 6. Architectural trade-offs

Discuss:

- Correctness / reliability
- Performance / throughput
- Contention
- Maintainability
- Scalability

## 7. Mini ADR

### Context

### Decision

### Alternatives considered

### Consequences

### Evidence

## 8. Conclusions

1.
2.
3.
