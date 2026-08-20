
package edu.eci.arsw.relicrush.game;

import edu.eci.arsw.relicrush.concurrency.ForgeLedger;
import edu.eci.arsw.relicrush.concurrency.LockPair;
import edu.eci.arsw.relicrush.gui.AdventurerState;
import edu.eci.arsw.relicrush.model.ForgeEvent;
import edu.eci.arsw.relicrush.model.ForgeStation;

import java.util.List;
import java.util.SplittableRandom;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CyclicBarrier;

/**
 * One player = one platform thread. This is intentional for the lab.
 */
public final class Adventurer extends Thread {
    private final int playerId;
    private final List<ForgeStation> stations;
    private final ForgeLedger ledger;
    private final CyclicBarrier roundStart;
    private final CyclicBarrier roundEnd;
    private final int rounds;
    private final SplittableRandom random;

    public static volatile int visualDelayMs = 0;

    private int score;

    private volatile AdventurerState visualState = AdventurerState.DONE_WAITING_BARRIER;

    public AdventurerState visualState() {
        return visualState;
    }

    private volatile ForgeStation currentFirst;
    private volatile ForgeStation currentSecond;

    public ForgeStation currentFirstStation() {
        return currentFirst;
    }

    public ForgeStation currentSecondStation() {
        return currentSecond;
    }

    public Adventurer(
            int playerId,
            List<ForgeStation> stations,
            ForgeLedger ledger,
            CyclicBarrier roundStart,
            CyclicBarrier roundEnd,
            int rounds) {
        super("adventurer-" + playerId);
        this.playerId = playerId;
        this.stations = stations;
        this.ledger = ledger;
        this.roundStart = roundStart;
        this.roundEnd = roundEnd;
        this.rounds = rounds;
        this.random = new SplittableRandom(1000L + playerId);
    }

    public int playerId() {
        return playerId;
    }

    public int score() {
        return score;
    }

    @Override
    public void run() {
        try {
            for (int round = 1; round <= rounds; round++) {
                roundStart.await();
                playTurn(round);
                roundEnd.await();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (BrokenBarrierException e) {
            // The coordinator may break the barrier if the game is aborted.
        }
    }

    private void playTurn(int round) {
        int firstIndex = random.nextInt(stations.size());
        int secondIndex;
        do {
            secondIndex = random.nextInt(stations.size());
        } while (secondIndex == firstIndex);

        ForgeStation first = stations.get(firstIndex);
        ForgeStation second = stations.get(secondIndex);

        currentFirst = first;
        currentSecond = second;
        visualState = AdventurerState.WAITING_FOR_STATION;

        LockPair.withBoth(first, second, () -> {
            visualState = AdventurerState.CRAFTING;

            if (visualDelayMs > 0) {
                try {
                    Thread.sleep(visualDelayMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }

            score++;
            ledger.record(new ForgeEvent(round, getName(), first.name(), second.name(), score));
        });

        visualState = AdventurerState.DONE_WAITING_BARRIER;
    }
}
