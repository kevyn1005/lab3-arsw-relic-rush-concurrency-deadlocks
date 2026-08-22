package edu.eci.arsw.relicrush.concurrency;

import edu.eci.arsw.relicrush.model.ForgeStation;

/**
 * Fix for the deadlock in the starter: lock the two stations by id order
 * (lower id first) instead of the order the caller passed them in.
 *
 * Adventurer.playTurn picks the two stations with random indices, so
 * without this rule two adventurers going for the same pair of stations
 * could lock them in opposite order and deadlock (that's what
 * DeadlockProbe was catching). If everyone always locks the lower id
 * first, that can't happen anymore: there is no pair of threads that can
 * each be holding what the other one needs.
 *
 * This only changes the order, not the granularity, so two adventurers
 * that want different stations still run at the same time. We are not
 * using one lock for the whole game.
 */
public final class LockPair {

    private LockPair() {
    }

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
}
