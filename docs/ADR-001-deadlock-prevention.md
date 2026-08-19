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

Acquire the two station monitors in a deterministic global order
instead of the order the caller supplied: always lock the
`ForgeStation` with the lower `id()` first, then the one with the
higher `id()`.

```java
public static void withBoth(ForgeStation first, ForgeStation second, Runnable action) {
    ForgeStation lowerId = first;
    ForgeStation higherId = second;
    if (first.id() > second.id()) {
        lowerId = second;
        higherId = first;
    }
    synchronized (lowerId) {
        synchronized (higherId) {
            action.run();
        }
    }
}
```

`ForgeStation.id()` is assigned once at station creation and never
changes, so every thread in the process agrees on the same total
order over stations. Two adventurers that want the same pair of
stations — regardless of which one they call `first` and which one
they call `second` — always attempt to lock them in the same
sequence, so the wait-for graph between them can no longer contain a
cycle. This is the standard **resource ordering / lock ordering**
technique for breaking circular wait, applied at the smallest possible
scope (this one static method), with no change to `ForgeStation`,
`Adventurer`, or `GameEngine`.

## Alternatives considered

1. **Single global lock around the whole craft operation** (e.g.
   `synchronized (GLOBAL_LOCK) { ... }` in `LockPair.withBoth`, or a
   game-wide lock in `GameEngine`). Trivially deadlock-free and easy to
   reason about, but it is explicitly disallowed by the lab and would
   defeat the purpose of the exercise: it would force every adventurer
   to wait for every other adventurer on every craft, even when their
   two station pairs share no station at all. With 8 stations there
   are 28 possible unordered pairs, so in practice most concurrent
   turns target disjoint pairs and should be able to run fully in
   parallel — a global lock throws that away.

2. **`tryLock` with timeout and retry**, using
   `java.util.concurrent.locks.ReentrantLock` instead of intrinsic
   monitors: a thread that cannot acquire the second lock within a
   timeout releases the first one and retries. This also removes
   circular wait (technically it removes "no preemption" instead, by
   letting a thread voluntarily give up a held resource), and can be a
   good fit when lock ordering isn't available or is hard to define.
   Here it was rejected because it needs `ForgeStation` to hold an
   explicit `Lock` field (losing the "the station is its own monitor"
   simplicity), adds retry/back-off tuning (how long to wait, how many
   retries, backoff strategy) that has no natural answer for this
   game, and can degrade into busy-retrying / near-livelock under the
   128-player stress configuration without careful tuning. Lock
   ordering achieves the same correctness with none of that
   complexity, because this game already has a natural, stable total
   order to use: station id.

3. **Deterministic lock ordering by station id (chosen).** Minimal,
   local change (one static method), keeps `ForgeStation` as a plain
   monitor object, preserves fine-grained per-pair locking, and needs
   no tuning or retry logic. Selected over the two alternatives above.

## Quality attributes affected

- **Correctness.** Eliminates the circular-wait condition, so
  `DeadlockProbe` no longer reports a deadlock (see Evidence). The
  station mutual-exclusion invariant is unaffected — it is enforced
  the same way as before (`synchronized` on the station object), just
  in a different, consistent order.
- **Performance / throughput.** No new contention is introduced. The
  ordering rule only decides *which* of the two locks is attempted
  first; it does not increase the total number of locks held or the
  time they are held, and independent (disjoint) station pairs remain
  fully concurrent. Stress runs at 8, 32 and 128 players (section 5 of
  `docs/REPORT.md`) all completed with `invariant=OK` on every round.
- **Maintainability.** The rule is a single, well-documented comparison
  in one method (`first.id() > second.id()`), so any future caller of
  `LockPair.withBoth` gets deadlock-free behavior automatically,
  without needing to know or repeat the ordering logic.
- **Scalability.** The fix does not change how contention scales with
  player/station ratio — that scaling is inherent to the game rules
  (more players sharing fewer stations means more legitimate waiting
  on the same station), not an artifact of the locking strategy.

## Evidence

**Before fix** (starter `LockPair`, unordered lock acquisition —
reproduced 2 out of 3 runs, matching the lab's note that the race is
intermittent):

```
$ java -cp target/classes edu.eci.arsw.relicrush.app.DeadlockProbe
DEADLOCK DETECTED
- probe-A-anvil-then-furnace waiting on edu.eci.arsw.relicrush.model.ForgeStation@1b28cdfa owned by probe-B-furnace-then-anvil
- probe-B-furnace-then-anvil waiting on edu.eci.arsw.relicrush.model.ForgeStation@53d8d10a owned by probe-A-anvil-then-furnace
```

**After fix** (deterministic lock ordering by station id — 8/8 clean
runs, reproduced twice: once in an isolated build environment and a
second time independently by chimi on the team's own Windows machine,
`hever` branch, `mvn clean test` green with JDK 21):

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

Positive:

- The game can no longer deadlock through `LockPair`, so
  `GameEngine.run()` always reaches `roundEnd` for every adventurer and
  the game terminates normally instead of being killed by the
  deadlock watchdog.
- No loss of concurrency: the fix changes lock *order*, not lock
  *granularity*, so throughput under stress (128 players / 8 stations)
  is unaffected by this change.
- The rule is trivial to audit: any code review can check "does this
  code lock two stations by ascending id?" without needing to trace
  call graphs.

Negative / trade-offs:

- The rule depends on every station having a stable, unique `id()`
  that is assigned once and never mutated. If a future change made
  station ids mutable, or introduced a second kind of exclusive
  resource without an id, the ordering rule would need to be
  redefined for it.
- All callers of `withBoth` **must** go through `LockPair` for any
  two-station acquisition; a future developer who writes
  `synchronized (a) { synchronized (b) { ... } }` directly, bypassing
  `LockPair`, would reintroduce circular wait. This is a convention,
  not something the compiler enforces.

## Risks

- **New exclusive resources.** If a future feature adds a third kind
  of exclusive resource (not a `ForgeStation`), or operations that
  need three or more stations at once, the two-argument ordering in
  `withBoth` would need to be generalized (e.g. sort a `List<ForgeStation>`
  by id and acquire in that order) rather than reused as-is.
- **Id collisions.** The ordering assumes `id()` values are unique
  across all stations in a game. `GameEngine.createStations` already
  guarantees this (`i + 1` for `i` in `0..count`), but nothing at the
  type level prevents a future caller from constructing two
  `ForgeStation` instances with the same id; that would make the
  `first.id() > second.id()` comparison ambiguous for that pair.
- **Bypassing `LockPair`.** As noted above, the safety property lives
  in one method by convention. Code review / a lint rule is the only
  guard against new code taking two station monitors directly.
