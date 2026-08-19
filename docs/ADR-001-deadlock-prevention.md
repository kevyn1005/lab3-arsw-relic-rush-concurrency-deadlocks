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

<!-- State the selected strategy. -->

## Alternatives considered

<!-- Compare at least two alternatives. -->

## Quality attributes affected

<!-- Correctness, performance, maintainability, scalability. -->

## Evidence

## Evidence

**Before fix** (starter `LockPair`, unordered lock acquisition):

```
PS C:\Users\Lenovo\Desktop\Uni\ARSW\Lab03> java -cp target/classes edu.eci.arsw.relicrush.app.DeadlockProbe
DEADLOCK DETECTED
- probe-A-anvil-then-furnace waiting on edu.eci.arsw.relicrush.model.ForgeStation@2626b418 owned by probe-B-furnace-then-anvil
- probe-B-furnace-then-anvil waiting on edu.eci.arsw.relicrush.model.ForgeStation@78308db1 owned by probe-A-anvil-then-furnace
```

**After fix** (deterministic lock ordering by station id):

```
PS C:\Users\Lenovo\Desktop\Uni\ARSW\Lab03> java -cp target/classes edu.eci.arsw.relicrush.app.DeadlockProbe
NO DEADLOCK DETECTED within 2 seconds.
If you already fixed LockPair, this is the expected result.
```
## Consequences

<!-- Positive and negative consequences. -->

## Risks

<!-- What could break this decision later? -->
