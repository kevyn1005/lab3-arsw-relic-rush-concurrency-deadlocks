# ARSW Lab 3 - Relic Rush - Delivery Report

## Team

| Student | ID | GitHub |
|---|---|---|
| | | kevyn1005 |
| | | (Juli/Dev) |
| | | heverthisday (chimi) |

<!-- TODO: fill in real names and student IDs before submitting. -->

Repository: `https://github.com/kevyn1005/lab3-arsw-relic-rush-concurrency-deadlocks.git`

Final commit: `SHA`
<!-- TODO: paste the final commit hash once Part V/VI are committed and pushed. -->

## 1. Baseline observations

- Command(s) executed: `java -cp target/classes edu.eci.arsw.relicrush.app.LedgerRaceProbe 64 5000` and `java -cp target/classes edu.eci.arsw.relicrush.app.RelicRushMain` with the default config (8 adventurers, 6 stations, 25 rounds). Both were run against the original starter commit (`a4bdd20`), before touching anything for this lab.
- What happened? `LedgerRaceProbe` lost most of the writes it was supposed to record: out of 320000 expected updates (64 threads x 5000 writes), only 7468 made it into `totalCrafted` and 305989 into the event list. Running `RelicRushMain` printed `invariant=BROKEN` from round 1, and since `LockPair` still had the unordered locking bug at the same time, most runs just froze and got killed by the game's own watchdog before finishing.
- Was the round invariant always preserved? No, not even once we let it run for more than a couple of rounds. Any time the race window in `ForgeLedger.record` got hit, `scoreSum`, `ledger.totalCrafted()` and `events.size()` stopped matching.
- Did the game stop unexpectedly? Yes, in most attempts. `GameEngine.startDeadlockWatchdog` picked up a real JVM deadlock through `ThreadMXBean.findDeadlockedThreads()` and called `System.exit(2)` well before the 25 rounds were done.

Evidence:

```text
$ java -cp target/classes edu.eci.arsw.relicrush.app.LedgerRaceProbe 64 5000
expected=320000 totalCrafted=7468 eventCount=305989 invariant=BROKEN

$ java -cp target/classes edu.eci.arsw.relicrush.app.RelicRushMain
Starting Relic Rush: adventurers=8, stations=6, rounds=25
ROUND 01 | scoreSum=8 | ledger=7 | events=8 | invariant=BROKEN
ROUND 02 | scoreSum=16 | ledger=15 | events=16 | invariant=BROKEN

*** DEADLOCK DETECTED BY GAME WATCHDOG ***
Run DeadlockProbe or jcmd <PID> Thread.print for a focused diagnosis.
The starter exits here so you do not have to kill a frozen process manually.
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
| `ForgeLedger.totalCrafted` (int) | `record()` did `int next = totalCrafted + 1; Thread.yield(); totalCrafted = next;`, which is a read-modify-write split into three separate steps. Two threads can read the same value before either one writes back, so one of the increments just gets lost. | `scoreSum == ledger.totalCrafted` | Wrap the whole `record()` body (increment plus list add) in a `synchronized` block on a private `Object lock`. `totalCrafted()`, `eventCount()` and `snapshot()` synchronize on the same lock, so anyone reading gets a value that was actually published, not a half-updated one. | The counter and the list have to change together: a relic only "counts" once both are updated. That's why one lock around both operations made more sense than trying to make each field atomic on its own (an `AtomicInteger` for the counter, say, would still race against the separate `ArrayList.add`). The lock lives inside `ForgeLedger` and has nothing to do with the `ForgeStation` monitors `LockPair` uses, so it doesn't add any contention with the station-locking part covered in section 4. |
| `ForgeLedger.events` (`ArrayList<ForgeEvent>`) | `ArrayList` was never meant to be written to from multiple threads at once. Concurrent `add()` calls can corrupt its internal bookkeeping or just drop elements, which is basically what we saw in the baseline run in section 1 (`eventCount=305989` instead of `320000`). | `ledger.totalCrafted == events.size()`, and both should equal the real number of `ForgeEvent`s recorded | Same fix: `events.add(event)` runs inside the same `synchronized (lock)` block as the counter increment, so two threads can never touch the list at the same time. `snapshot()` also takes the lock before calling `List.copyOf(events)`, so it never reads a list mid-write. | We considered swapping `ArrayList` for something like `CopyOnWriteArrayList`, but that only solves half the problem: it makes `add()` safe on its own, but doesn't make the counter and the list update as one atomic unit, which is what the invariant actually needs. A single small lock around both writes is enough, and it doesn't touch anything outside `ForgeLedger`, so the station locking in `LockPair` stays completely independent. |

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

**What condition did you break?** Circular wait. We didn't touch the
other three: mutual exclusion on each station has to stay (that's the
whole point of the game), hold-and-wait is just what a `synchronized`
block does while it's blocked, and Java doesn't give you a way to
preempt a thread that's holding a monitor anyway. Circular wait was the
only one we could actually get rid of without weakening exclusivity,
by making sure two threads can never end up wanting the same two
stations in opposite order.

**How did you preserve concurrency between independent forge operations?**
`LockPair.withBoth` now picks the lock order based on `ForgeStation.id()`
instead of the order the caller passed the arguments in: it always
grabs the lower-id station first, then the higher one (details in
`docs/ADR-001-deadlock-prevention.md`). That's lock ordering, not a
global lock. Each pair of stations is still guarded by its own two
monitors, and two adventurers whose stations don't overlap (say one
craft uses Anvil and Furnace while another uses Lens and Altar) keep
running at the same time as before. Only adventurers that actually
want the same station end up waiting on each other, which is really
the minimum amount of waiting the "no two incompatible crafts on the
same station" rule requires anyway.

## 5. Verification

We ran these three configurations twice, once while building the fix
and again by chimi on the team's own machine (JDK 21, `mvn clean test`
passing) to make sure it wasn't just working in one environment. Both
runs matched.

| Players | Stations | Rounds | Deadlock? | Invariant result |
|---:|---:|---:|---|---|
| 8 | 6 | 50 | No, 8/8 clean `DeadlockProbe` runs on both machines | OK, 50/50 rounds `invariant=OK`, finished normally, 400/400/400 |
| 32 | 8 | 100 | No | OK, 100/100 rounds `invariant=OK`, finished normally, 3200/3200/3200 |
| 128 | 8 | 100 | No | OK, 100/100 rounds `invariant=OK`, finished normally, 12800/12800/12800 |

Evidence (excerpt from chimi's run on the `hever` branch, full logs
available on request):

```text
PS ...\lab3-arsw-relic-rush-concurrency-deadlocks> for ($i=1; $i -le 8; $i++) { java -cp target/classes edu.eci.arsw.relicrush.app.DeadlockProbe }
NO DEADLOCK DETECTED within 2 seconds.
If you already fixed LockPair, this is the expected result.
... (repeated 8/8 times, 0 deadlocks)

PS ...> java -cp target/classes edu.eci.arsw.relicrush.app.InvariantProbe 8 6 50
ROUND 01 | scoreSum=8 | ledger=8 | events=8 | invariant=OK
...
ROUND 50 | scoreSum=400 | ledger=400 | events=400 | invariant=OK
Total by players : 400
Ledger total     : 400
Ledger events    : 400

PS ...> java -cp target/classes edu.eci.arsw.relicrush.app.InvariantProbe 32 8 100
ROUND 01 | scoreSum=32 | ledger=32 | events=32 | invariant=OK
...
ROUND 100 | scoreSum=3200 | ledger=3200 | events=3200 | invariant=OK
Total by players : 3200
Ledger total     : 3200
Ledger events    : 3200

PS ...> java -cp target/classes edu.eci.arsw.relicrush.app.InvariantProbe 128 8 100
ROUND 01 | scoreSum=128 | ledger=128 | events=128 | invariant=OK
...
ROUND 100 | scoreSum=12800 | ledger=12800 | events=12800 | invariant=OK
Total by players : 12800
Ledger total     : 12800
Ledger events    : 12800
```

`grep -c "invariant=BROKEN"` returns `0` on all three logs, on both
machines. `scoreSum == ledger.totalCrafted == events.size()` held for
every single round in every config, and the process always finished
normally instead of getting killed by the watchdog.

## 6. Architectural trade-offs

**Correctness / reliability.** Two invariants matter here. The ledger
one (`scoreSum == totalCrafted == events.size()`) is protected because
the counter increment and the list append now happen as one atomic
step under `ForgeLedger`'s lock. The station one (no two incompatible
crafts using the same station at once) is protected by the
`synchronized` blocks in `LockPair`. Section 1 shows both breaking on
the unfixed starter, and section 5 shows both holding across three
stress configurations, up to 12800 relics crafted, with `DeadlockProbe`
clean over 8 runs.

**Performance / throughput.** There are two places contention can show
up: inside `ForgeLedger.record()`, since every craft in the game goes
through the same private lock, and on individual `ForgeStation`
monitors, when two adventurers want the same station at the same time.
We stayed away from a single global lock over the whole craft
operation (the lab rules it out anyway) because it would make every
adventurer wait for every other adventurer, even ones going for
completely different stations. With 8 stations there are 28 possible
pairs, so most turns don't even collide and can run fully in parallel.
The ordering rule in `LockPair` only changes which station gets locked
first, it doesn't add any waiting beyond what mutual exclusion on a
shared station already forces.

**Contention.** `ForgeLedger`'s lock is the closest thing to a
bottleneck since every thread hits it every round, but the critical
section is tiny (one increment, one `ArrayList.add`), so it's held for
a very short time compared to the rest of what an adventurer does.
Station contention scales with players divided by stations: more
players sharing fewer stations means more collisions on the same
station, which is expected given that stations are supposed to be
exclusive resources, not a symptom of a bad design.

**Maintainability.** Lock ownership is easy to follow: `ForgeStation`
objects are the only things ever used as monitors, and the only place
that locks them is `LockPair.withBoth`. The ordering rule is a
one-line comparison (`first.id() > second.id()`) documented right
there in the code and in the ADR, so anyone who calls `withBoth` later
gets the deadlock-free behavior automatically without having to know
the rule exists.

**Scalability.** As the player count grows with the station count
fixed, station contention goes up, because more people are competing
for the same small set of exclusive resources. That's just how the
game works, not something the locking strategy causes. The ledger
lock's critical section stays constant size no matter how many players
there are, so ledger contention grows with the craft rate, not faster
than that. The 128-player run (12800 relics, four times the 32-player
run's total) still came out with zero broken rounds, which matches
that reasoning.

## 7. Mini ADR

### Context

Full version in `docs/ADR-001-deadlock-prevention.md`. Short version:
the starter locked `first` then `second` in whatever order the caller
passed them, and since `Adventurer.playTurn` picks the two stations
with random indices, two adventurers could ask for the same pair in
opposite order. That's a circular wait, and `DeadlockProbe` confirmed
it.

### Decision

Lock the two stations by `ForgeStation.id()` order instead of
call-site order: always take the lower id first. This gets rid of the
circular wait but keeps two separate `synchronized` blocks per craft,
so it's still fine-grained, not one big lock.

### Alternatives considered

1. **One global lock around the whole craft operation.** Easiest to
   reason about, but the lab explicitly forbids it, and it would make
   every adventurer wait for every other one regardless of which
   stations they actually need.
2. **`tryLock` with a timeout using `ReentrantLock`.** A thread that
   can't get the second lock in time backs off and retries instead of
   blocking forever. It works, but it needs `ForgeStation` to hold an
   explicit `Lock` object instead of just being its own monitor, and
   picking a good timeout/retry policy isn't obvious for this game.
3. **Lock ordering by station id (what we went with).** Small,
   localized change, keeps `ForgeStation` as a plain monitor, and
   doesn't need any tuning.

### Consequences

See "Consequences" and "Risks" in `docs/ADR-001-deadlock-prevention.md`.

### Evidence

Section 5 above, and the "Evidence" section of the ADR: before/after
`DeadlockProbe` output plus the stress runs.

## 8. Conclusions

1. The starter actually had two independent bugs. The ledger race
   (non-atomic counter plus an `ArrayList` written from multiple
   threads) broke the round invariant on its own, and the unordered
   nested locking in `LockPair` was a separate liveness problem that
   could freeze the game even if the ledger had been fine. Fixing one
   didn't depend on fixing the other.
2. Neither fix needed to serialize the game. `ForgeLedger` just needed
   one small private lock around two writes that have to happen
   together, and `LockPair` only needed a change in lock *order*, not
   a bigger lock. 8, 32 and 128 adventurers all finished with
   `invariant=OK` on every round.
3. The deadlock went away by removing one Coffman condition (circular
   wait) through a fixed order over the resources. That's a general
   technique, not something specific to this game, and it applies any
   time a thread needs to hold more than one exclusive resource at a
   time.
