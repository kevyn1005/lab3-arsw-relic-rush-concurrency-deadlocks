package edu.eci.arsw.relicrush.concurrency;

import edu.eci.arsw.relicrush.model.ForgeStation;

/**
 * Deadlock-prevention strategy: resource ordering.
 *
 * Every caller of {@link #withBoth} may ask for the same two stations in
 * either order (see {@code Adventurer.playTurn}, which picks random
 * indices). If two threads acquired the stations in the order the caller
 * happened to supply, thread A could hold station X while waiting for Y at
 * the same time thread B holds Y while waiting for X -&gt; circular wait,
 * one of the four Coffman conditions, and a deadlock.
 *
 * The fix removes that condition without giving up fine-grained locking:
 * instead of locking in "first, second" (caller) order, we always lock the
 * station with the lower {@link ForgeStation#id()} first. Because the id is
 * a stable, total order shared by every thread in the game, two threads
 * that want the same pair of stations always attempt to acquire them in the
 * same sequence, so a cycle in the wait-for graph can no longer form.
 * Station pairs that do not overlap at all are untouched by this rule and
 * keep running fully in parallel — there is still no single global lock.
 */
public final class LockPair {

    private LockPair() {
    }

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
}
