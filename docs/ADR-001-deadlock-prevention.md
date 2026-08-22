# ADR-001: Deadlock prevention strategy

## Context

The starter `LockPair.withBoth` acquires the two `ForgeStation`
monitors in the order the caller passes them. Since
`Adventurer.playTurn` picks `first` and `second` with random indices
each turn, two adventurers can request the same pair of stations in
opposite order (e.g. A calls `withBoth(Anvil, Furnace)` while B calls
`withBoth(Furnace, Anvil)`). If A holds Anvil and B holds Furnace at
the same time, each blocks waiting for the station the other holds,
and neither can release what it already has. This nested-lock pattern
was confirmed with `DeadlockProbe`:

```
DEADLOCK DETECTED
- probe-A-anvil-then-furnace waiting on ForgeStation@2626b418 owned by probe-B-furnace-then-anvil
- probe-B-furnace-then-anvil waiting on ForgeStation@78308db1 owned by probe-A-anvil-then-furnace
```

This breaks the round invariant `scoreSum == ledger.totalCrafted ==
events.size()`, but more critically it is a **liveness** failure: the
`GameEngine` waits on `roundEnd` for every adventurer to finish its
turn, and a deadlocked adventurer never does — the game freezes
instead of just producing a wrong number.

## Decision

Lock the two station monitors in a fixed order instead of the order
the caller happens to supply: always lock the `ForgeStation` with the
lower `id()` first, then the other one.

```java
public static void withBoth(ForgeStation first, ForgeStation second, Runnable action) {
    ForgeStation lower = first;
    ForgeStation higher = second;
    if (first.id() > second.id()) {
        lower = second;
        higher = first;
    }
    synchronized (lower) {
        synchronized (higher) {
            action.run();
        }
    }
}
```

`ForgeStation.id()` is set once when the station is created and never
changes, so every thread agrees on the same ordering. Two adventurers
that want the same pair of stations, no matter which one they pass as
`first` and which as `second`, will always try to lock them in the
same order, so there's no way for a cycle to form in the wait-for
graph anymore. This is the usual resource-ordering approach to
breaking circular wait, and it only touches this one method, nothing
in `ForgeStation`, `Adventurer` or `GameEngine` had to change.

## Alternatives considered

1. **One global lock around the whole craft operation**, for example
   `synchronized (GLOBAL_LOCK) { ... }` inside `LockPair.withBoth`.
   Deadlock-free and about as simple as it gets, but the lab rules it
   out, and for good reason: it would make every adventurer wait for
   every other one on every craft, even when their two stations don't
   overlap at all. With 8 stations there are 28 possible pairs, so
   most turns don't actually collide, and a global lock throws that
   concurrency away for no reason.

2. **`tryLock` with a timeout**, using
   `java.util.concurrent.locks.ReentrantLock` instead of plain
   monitors: if a thread can't grab the second lock in time, it lets
   go of the first one and tries again later. This also gets rid of
   the deadlock, just from a different angle (it removes "no
   preemption" instead of circular wait, since the thread voluntarily
   gives up what it's holding). We didn't go with it because it means
   `ForgeStation` needs an explicit `Lock` field instead of being its
   own monitor, and picking a reasonable timeout and retry/backoff
   policy isn't obvious, especially under the 128-player stress test,
   where a bad choice could turn into a lot of wasted retries.

3. **Lock ordering by station id (what we picked).** Small, local
   change, `ForgeStation` stays a plain monitor object, locking is
   still fine-grained per pair, and there's no tuning involved.

## Quality attributes affected

- **Correctness.** Gets rid of the circular-wait condition, so
  `DeadlockProbe` stops reporting a deadlock (see Evidence below). The
  mutual-exclusion invariant on stations doesn't change at all, it's
  still enforced with `synchronized` on the station object, just in a
  consistent order now.
- **Performance / throughput.** Doesn't add any new contention. The
  ordering rule only decides which of the two locks gets tried first;
  it's the same number of locks held for the same amount of time, and
  station pairs that don't overlap still run fully in parallel. The
  stress runs at 8, 32 and 128 players in `docs/REPORT.md` section 5
  all finished with `invariant=OK` every round.
- **Maintainability.** It's one comparison in one method
  (`first.id() > second.id()`), so anyone who calls
  `LockPair.withBoth` later gets the deadlock-free behavior for free,
  without having to know the rule exists.
- **Scalability.** Doesn't change how contention scales with the
  player-to-station ratio. That scaling comes from the game rules
  themselves (more players sharing fewer stations means more waiting),
  not from how the locking is implemented.

## Evidence

**Before fix** (starter `LockPair`, unordered lock acquisition, showed
up in 2 out of 3 runs, which matches what the lab says about the race
being intermittent):

```
$ java -cp target/classes edu.eci.arsw.relicrush.app.DeadlockProbe
DEADLOCK DETECTED
- probe-A-anvil-then-furnace waiting on edu.eci.arsw.relicrush.model.ForgeStation@1b28cdfa owned by probe-B-furnace-then-anvil
- probe-B-furnace-then-anvil waiting on edu.eci.arsw.relicrush.model.ForgeStation@53d8d10a owned by probe-A-anvil-then-furnace
```

**After fix** (lock ordering by station id, 8/8 clean runs, checked
twice: once while building the fix and again independently by chimi on
the team's own Windows machine, `hever` branch, `mvn clean test` green
on JDK 21):

```
PS ...\lab3-arsw-relic-rush-concurrency-deadlocks> for ($i=1; $i -le 8; $i++) { java -cp target/classes edu.eci.arsw.relicrush.app.DeadlockProbe }
NO DEADLOCK DETECTED within 2 seconds.
If you already fixed LockPair, this is the expected result.
... (repeated 8 times, 8/8 clean, both machines)
```

**Stress verification after the fix** (see `docs/REPORT.md` section 5
for full output, also reproduced on chimi's machine): `InvariantProbe`
at 8/6/50, 32/8/100 and 128/8/100 all finished with `invariant=OK` on
every round (400/400/400, 3200/3200/3200, 12800/12800/12800) and 0
occurrences of `invariant=BROKEN` in the logs, on both machines.

## Consequences

Good:

- The game can't deadlock through `LockPair` anymore, so
  `GameEngine.run()` always reaches `roundEnd` for every adventurer and
  the game finishes normally instead of getting killed by the
  watchdog.
- No concurrency lost. The fix only changes the *order* locks are
  taken in, not how many locks or how coarse they are, so throughput
  under stress (128 players, 8 stations) isn't affected.
- Easy to check: anyone reviewing the code just has to confirm it
  locks stations by ascending id, no need to trace through call
  graphs.

Not so good:

- It only works because every station has a stable, unique `id()` set
  once and never changed. If ids ever became mutable, or a second kind
  of exclusive resource without an id got added, the ordering rule
  would need to be reworked.
- Every caller that needs two stations has to go through `LockPair`.
  If someone writes `synchronized (a) { synchronized (b) { ... } }`
  directly somewhere else, bypassing `LockPair`, the deadlock comes
  right back. Nothing in the compiler stops that, it's just a
  convention we have to follow.

## Risks

- **New exclusive resources.** If a future feature adds something
  else that needs exclusive access besides `ForgeStation`, or an
  operation that needs three or more stations at once, the
  two-argument ordering in `withBoth` won't be enough as-is. It would
  need to become something like sorting a `List<ForgeStation>` by id
  and locking them in that order.
- **Id collisions.** The whole thing assumes every station's `id()` is
  unique. `GameEngine.createStations` already guarantees that, but
  nothing stops some future code from creating two `ForgeStation`
  objects with the same id, which would make the ordering comparison
  meaningless for that pair.
- **Someone skips `LockPair`.** Since the safety property only lives
  in this one method by convention, the only real guard against new
  code locking two stations directly is code review.
