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

- Command(s) executed: `java -cp target/classes edu.eci.arsw.relicrush.app.LedgerRaceProbe 64 5000` and `java -cp target/classes edu.eci.arsw.relicrush.app.RelicRushMain` (defaults: 8 adventurers, 6 stations, 25 rounds), both against the untouched starter commit (`a4bdd20`, before any Lab 3 fix).
- What happened? `LedgerRaceProbe` lost most of the expected updates: with 64 threads doing 5000 writes each (320000 expected), only 7468 survived in `totalCrafted` and 305989 in the event list. `RelicRushMain` printed `invariant=BROKEN` on the very first rounds, and — because `LockPair` was still using unordered nested locking at the same time — the run frequently froze entirely and was killed by the game's own deadlock watchdog (`GameEngine.startDeadlockWatchdog`) instead of finishing.
- Was the round invariant always preserved? No. Whenever the race window in `ForgeLedger.record` was hit, `scoreSum`, `ledger.totalCrafted()` and `events.size()` diverged.
- Did the game stop unexpectedly? Yes, in most runs. The watchdog detected a real JVM deadlock (via `ThreadMXBean.findDeadlockedThreads()`) and called `System.exit(2)` before all 25 rounds completed.

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
| `ForgeLedger.totalCrafted` (int) | `record()` did `int next = totalCrafted + 1; Thread.yield(); totalCrafted = next;` — a non-atomic read-modify-write. Two threads can both read the same value before either writes back, so one increment is lost. | `scoreSum == ledger.totalCrafted` | Guard the whole `record()` body (increment + list add) with a single private `Object lock` and `synchronized` blocks; `totalCrafted()`, `eventCount()` and `snapshot()` also synchronize on the same lock so readers see a consistent, published value. | The two writes (counter and list) must stay atomic *together* — a relic is only "real" once both are updated, so one lock protecting both operations is simpler and safer than trying to make each field independently atomic (e.g. `AtomicInteger` for the counter would still race against the separate list add). The lock is private to `ForgeLedger` and unrelated to the `ForgeStation` monitors used by `LockPair`, so it never contends with — or participates in — the station-locking scheme analyzed in section 4. |
| `ForgeLedger.events` (`ArrayList<ForgeEvent>`) | `ArrayList` is not designed for concurrent structural modification (`add`); concurrent writers can corrupt its internal array/size bookkeeping or silently lose elements, which is exactly what the baseline evidence in section 1 shows (`eventCount=305989` instead of `320000`). | `ledger.totalCrafted == events.size()` and `events.size() == number of ForgeEvent entries` | Same fix as above: `events.add(event)` happens inside the same `synchronized (lock)` block as the counter increment, so the list is never mutated by two threads at once. `List.copyOf(events)` in `snapshot()` is also taken under the lock so it can't observe a half-written list. | A concurrent collection (e.g. `CopyOnWriteArrayList`) was considered but rejected: it would make `events.add` thread-safe in isolation, but would *not* make the pair (counter, list) atomic with respect to each other, which is the actual invariant the game needs. A single narrow lock around the two related writes is the minimal mechanism that satisfies the invariant, and it does not serialize anything outside `ForgeLedger` — station locking in `LockPair` stays fully independent and concurrent. |

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

**What condition did you break?** Circular wait. The other three Coffman
conditions (mutual exclusion on each station, hold-and-wait while blocked
on `synchronized`, and no preemption of a held monitor) are inherent to
using intrinsic locks for exclusive stations and are not things we want to
remove — mutual exclusion is required by the game's rules, and Java gives
us no way to preempt a `synchronized` block anyway. The one condition we
*can* remove without weakening exclusivity is circular wait, by making
sure two threads can never end up wanting the same two stations in
opposite orders.

**How did you preserve concurrency between independent forge operations?**
`LockPair.withBoth` now derives the acquisition order from
`ForgeStation.id()` instead of from the order the caller passed the two
stations in: it always locks the station with the lower id first, then
the one with the higher id (see `docs/ADR-001-deadlock-prevention.md`).
This is a resource-ordering strategy, not a global lock — each pair of
stations still has its own two monitors, and any two adventurers whose
station pairs don't overlap (e.g. `{Anvil, Furnace}` vs. `{Lens, Altar}`)
still run their `withBoth` blocks fully in parallel. Only adventurers that
are actually contending for the *same* station(s) ever wait on each other,
which is the minimum synchronization the invariant "a station cannot be
used by two incompatible craft operations at once" requires.

## 5. Verification

These three configurations were run twice: once in an isolated
verification environment while building the fix, and a second time by
chimi on the team's own Windows machine (`mvn clean test` green,
JDK 21, real network access to Maven Central) to reproduce the same
result independently. Both runs agree exactly.

| Players | Stations | Rounds | Deadlock? | Invariant result |
|---:|---:|---:|---|---|
| 8 | 6 | 50 | No — 8/8 clean `DeadlockProbe` runs, reproduced independently on both machines | OK — 50/50 rounds `invariant=OK`, game finished normally, 400/400/400 |
| 32 | 8 | 100 | No | OK — 100/100 rounds `invariant=OK`, game finished normally, 3200/3200/3200 |
| 128 | 8 | 100 | No | OK — 100/100 rounds `invariant=OK`, game finished normally, 12800/12800/12800 |

Evidence (excerpt from chimi's local run on the `hever` branch, full
logs available on request):

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

`grep -c "invariant=BROKEN"` on all three logs returns `0` on both
machines. In every config, `scoreSum == ledger.totalCrafted ==
events.size()` held for every single round, and the process exited
normally (no watchdog abort).

## 6. Architectural trade-offs

**Correctness / reliability.** Two invariants are protected: the ledger
invariant (`scoreSum == totalCrafted == events.size()`), guaranteed by
making the counter increment and the list append atomic under one lock in
`ForgeLedger`; and the mutual-exclusion invariant on stations
("a forge station cannot be used simultaneously by two incompatible craft
operations"), guaranteed by the `synchronized` blocks in `LockPair` around
each station object. The evidence in sections 1 and 5 shows both
invariants failing on the unfixed starter and holding across three stress
configurations (8/6/50, 32/8/100, 128/8/100 — up to 12800 relics crafted)
after the fix, with `DeadlockProbe` clean across 8 consecutive runs.

**Performance / throughput.** Lock contention can appear in two places:
inside `ForgeLedger.record()`, since every successful craft in the whole
game serializes on the same private lock; and on individual
`ForgeStation` monitors, when two or more adventurers want the *same*
station at the *same* time. A single global lock over the entire craft
operation was deliberately avoided (the lab forbids it) because it would
force every adventurer to wait for every other adventurer even when their
station pairs don't overlap at all — with 8 stations there are 28 possible
pairs, so most concurrent turns target disjoint pairs and can run truly in
parallel. `LockPair`'s ordering rule only changes *which* station is
locked first; it does not add any new waiting beyond what mutual exclusion
on shared stations already requires, so the achievable concurrency is the
same as an unordered — but correct — fine-grained scheme.

**Contention.** `ForgeLedger`'s lock is the one point every thread
touches every round, so it is the closest thing to a bottleneck in this
design, but the critical section inside it is tiny (an increment and an
`ArrayList.add`), so contention there is short-lived compared to the
random work adventurers do picking stations. Station contention scales
with `players / stations`: more players sharing fewer stations increases
the chance two adventurers want the same station, which is expected and
desired (it's what "exclusive resource" means), not a bug.

**Maintainability.** Lock ownership is explicit and local: `ForgeStation`
instances are the only objects ever used as monitors, and they are only
ever locked from `LockPair.withBoth`, so there is exactly one place in the
codebase where station locking rules live. The ordering rule itself is a
one-line comparison (`first.id() > second.id()`) documented directly in
`LockPair`'s Javadoc and in `docs/ADR-001-deadlock-prevention.md`, so
anyone adding a new caller of `withBoth` automatically gets deadlock-free
behavior without having to know the rule — they just pass two stations,
in any order.

**Scalability.** As the number of players grows while the number of
stations stays fixed, the probability that two adventurers want the same
station in the same round increases, so *station* contention grows —
that's inherent to the game rules, not to the implementation. The
`ForgeLedger` lock's critical section does not grow with player count (it
is O(1) per craft), so ledger contention grows linearly with the craft
rate, not superlinearly. The 128-player / 8-station stress run in section
5 (3200 → 12800 relics vs. the 32-player run) still finished with 0
broken rounds, which is consistent with that analysis.

## 7. Mini ADR

### Context

See `docs/ADR-001-deadlock-prevention.md` for the full ADR. In short: the
starter locked `first` then `second` in caller-supplied order, and because
`Adventurer.playTurn` picks stations with random indices, two adventurers
could request the same pair of stations in opposite order, creating a
circular wait confirmed by `DeadlockProbe`.

### Decision

Order lock acquisition deterministically by `ForgeStation.id()` (always
lock the lower id first) instead of by call-site argument order. This
removes the circular-wait condition while keeping two independent
`synchronized` blocks per craft (fine-grained, not global).

### Alternatives considered

1. **Single global lock around the whole craft operation.** Simplest to
   reason about, but explicitly forbidden by the lab and would serialize
   every adventurer regardless of which stations they need, destroying
   the concurrency the game is meant to exercise.
2. **`tryLock` with timeout/back-off using `java.util.concurrent.locks.ReentrantLock`.**
   Avoids deadlock by giving up and retrying instead of blocking forever,
   but adds retry/back-off complexity and can livelock or waste CPU under
   high contention; it also changes `ForgeStation` from a plain monitor
   object to something that needs an explicit `Lock` field.
3. **Deterministic lock ordering by station id (chosen).** Minimal code
   change, keeps `ForgeStation` as its own monitor, preserves
   fine-grained locking, and is a textbook resource-ordering solution to
   circular wait.

### Consequences

See "Consequences" and "Risks" in `docs/ADR-001-deadlock-prevention.md`.

### Evidence

See section 5 above and the "Evidence" section of the ADR (before/after
`DeadlockProbe` output, plus 8 additional clean runs after the fix).

## 8. Conclusions

1. The two bugs the starter shipped with were independent: the ledger
   race (non-atomic counter + concurrently-mutated `ArrayList`) broke the
   round invariant even when no deadlock occurred, while the unordered
   nested locking in `LockPair` was a separate liveness bug that could
   freeze the whole game regardless of whether the ledger was correct.
   Both had to be fixed, but neither fix depended on the other.
2. Neither fix required serializing the game: `ForgeLedger` needed one
   small, private lock around two related writes, and `LockPair` needed
   only a change of *order*, not a coarser lock — 8, 32 and 128 adventurers
   all completed their full round counts with `invariant=OK` on every
   single round.
3. Deadlock prevention here came from removing one Coffman condition
   (circular wait) via a total order over resources, which is a general
   pattern applicable any time multiple threads need more than one
   exclusive resource at a time — not specific to this game.
