# ARSW Lab 3 - Relic Rush - Delivery Report

## Team

| Student                         | ID | GitHub |
|---------------------------------|---|---|
| KEVYN DANIEL FORERO GONZALEZ    | 1000095428|kevyn1005 |
| MARIA JULIANA RODRIGUEZ CAICECO |1000095732 |JuliRodC|
| HEVER BARRERA BATERO            | 1000094509|heverthisday |

Repository: `https://github.com/kevyn1005/lab3-arsw-relic-rush-concurrency-deadlocks.git`

Final commit: `SHA`

## 1. Baseline observations

- Command(s) executed:
>java -cp target/classes edu.eci.arsw.relicrush.app.LedgerRaceProbe 64 5000

>java -cp target/classes edu.eci.arsw.relicrush.app.RelicRushMain
- What happened?
> LedgerRaceProbe lost most of the writes it was supposed to record: out of 320000 expected updates (64 threads × 5000 writes), only 7468 made it into totalCrafted, and 305989 into the event list. Running RelicRushMain printed invariant=BROKEN starting from round 1, and since LockPair still had the unordered locking bug at the same time, most runs simply froze and got killed by the game's own watchdog before finishing
- Was the round invariant always preserved?
> No, not even once we let it run for more than a couple of rounds. Any time the race window in ForgeLedger.record got hit, scoreSum, ledger.totalCrafted(), and events.size() stopped matching each other
- Did the game stop unexpectedly?
> Yes, in most attempts. GameEngine.startDeadlockWatchdog detected a real JVM deadlock through ThreadMXBean.findDeadlockedThreads() and called System.exit(2) well before the 25 rounds were completed.
Evidence:

```text
PS C:\Users\Kevyn\Desktop\9 semestre\ARSW repos\Lab03> java -cp target/classes edu.eci.arsw.relicrush.app.LedgerRaceProbe
expected=64000 totalCrafted=2851 eventCount=55562 invariant=BROKEN

PS C:\Users\Kevyn\Desktop\9 semestre\ARSW repos\Lab03> java -cp target/classes edu.eci.arsw.relicrush.app.LedgerRaceProbe 64 5000
expected=320000 totalCrafted=6304 eventCount=285686 invariant=BROKEN

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

| Shared state | Problem | Invariant at risk | Solution | Why this solution?                                                                                                                                                                                                                                                                                                                                                                               |
|---|---|---|---|--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `totalCrafted` (int, en `ForgeLedger`) | The read-modify-write operation is not atomic (int next = totalCrafted + 1; ...; totalCrafted = next;). If two threads read the same value at the same time, one of the increments can be lost. | `scoreSum == ForgeLedger.totalCrafted == eventCount` | Wrap the whole record() body (the increment and the list add) in a synchronized block using a private Object lock. totalCrafted(), eventCount(), and snapshot() use the same lock, so the values read are always consistent and fully updated, instead of seeing a partially updated state. | The counter and the list need to be updated at the same time because a relic only counts as registered when both are updated. That’s why it makes more sense to use one lock for both operations instead of making each field atomic separately. For example, even if we used an AtomicInteger for the counter, there could still be problems because the ArrayList.add would happen separately. |
| `events` (`ArrayList<ForgeEvent>`, en `ForgeLedger`) | ArrayList is not thread-safe. If several threads call add() at the same time, some events can be lost or the list can end up in an inconsistent state. This can be seen with 64 threads × 5000 iterations, where eventCount=285686 instead of the expected 320000 | scoreSum == ForgeLedger.totalCrafted == eventCount | Same fix: events.add(event) runs inside the same synchronized (lock) block as the counter increment, so two threads can never touch the list at the same time. snapshot() also takes the lock before calling List.copyOf(events), so it never reads a list mid-write. |  We ruled out CopyOnWriteArrayList because it copies the entire array every time add() is called, which can be expensive when there are frequent writes. That’s exactly what happens in this game, since every successful crafting operation writes to the list.                                                                                                |

## 4. Deadlock diagnosis

### 4.1 Evidence

```
PS C:\Users\Lenovo\Desktop\Uni\ARSW\Lab03> java -cp target/classes edu.eci.arsw.relicrush.app.DeadlockProbe
DEADLOCK DETECTED
- probe-A-anvil-then-furnace waiting on edu.eci.arsw.relicrush.model.ForgeStation@2626b418 owned by probe-B-furnace-then-anvil
- probe-B-furnace-then-anvil waiting on edu.eci.arsw.relicrush.model.ForgeStation@78308db1 owned by probe-A-anvil-then-furnace
```


### 4.2 Coffman conditions in Relic Rush

- Mutual exclusion: Each `ForgeStation` is used as the monitor object
  of a `synchronized` block in `LockPair.withBoth`. Only one thread can
  hold the monitor of a given station at a time.

- Hold and wait: In `LockPair.withBoth`, the thread acquires the lock
  on `first` and keeps holding it while it blocks waiting for the lock
  on `second`, never releasing `first` during that wait. The
  `sleepQuietly(2)` call widens this window on purpose, making the
  deadlock easier to reproduce.

- No preemption: Java never forces a thread to release a `synchronized`
  lock; only the owning thread can release it, by exiting the block.
  Since the thread is blocked waiting for the second lock, it never
  exits the block and therefore never releases the first one.

- Circular wait: In `Adventurer.playTurn`, `first` and `second` are
  picked with random indices, so one adventurer may call
  `LockPair.withBoth(Anvil, Furnace)` while another calls
  `LockPair.withBoth(Furnace, Anvil)`, creating a circular wait cycle
  between the two threads — confirmed by the `DeadlockProbe` evidence
  in section 4.1.

### 4.3 Wait-for graph

![img.png](Diagram.png)

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
