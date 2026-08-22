package edu.eci.arsw.relicrush.game;

import edu.eci.arsw.relicrush.concurrency.ForgeLedger;
import edu.eci.arsw.relicrush.model.ForgeStation;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CyclicBarrier;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.util.concurrent.atomic.AtomicBoolean;

public final class GameEngine {
    private final GameConfig config;
    private final ForgeLedger ledger = new ForgeLedger();
    private final List<ForgeStation> stations;
    private final List<Adventurer> adventurers = new ArrayList<>();
    private final CyclicBarrier roundStart;
    private final CyclicBarrier roundEnd;
    private final AtomicBoolean finished = new AtomicBoolean(false);

    // --- Control de pausa / detención ---
    private final Object pauseLock = new Object();
    private volatile boolean paused = false;
    private volatile boolean stopRequested = false;

    public static volatile int roundDelayMs = 0; // solo la GUI lo activa
    private volatile int currentRound = 0;

    // Se actualiza una sola vez por ronda, justo despues de roundEnd.await(),
    // que es el unico momento en que todos los aventureros ya terminaron su
    // turno. Si se llamara invariantOk() en cualquier otro momento (por
    // ejemplo desde el Timer de la GUI mientras alguien todavia esta
    // craftando) podria dar ROTO de forma pasajera sin que sea un bug real,
    // porque score++ y ledger.record() no pasan en el mismo instante. Este
    // campo evita ese falso positivo: la GUI siempre lee el ultimo resultado
    // valido, calculado en el momento correcto.
    private volatile boolean lastInvariantOk = true;

    public boolean lastInvariantOk() {
        return lastInvariantOk;
    }

    public int currentRound() {
        return currentRound;
    }

    public List<ForgeStation> stations() {
        return stations;
    }

    public List<Adventurer> adventurers() {
        return adventurers;
    }

    public boolean isPaused() {
        return paused;
    }

    public boolean isStopped() {
        return stopRequested;
    }

    public boolean isFinished() {
        return finished.get();
    }

    // Misma formula que ya se usaba en printRoundSnapshot: si scoreSum,
    // ledger.totalCrafted() y ledger.eventCount() no coinciden es porque algo
    // se corrio sin pasar por LockPair o sin pasar por ForgeLedger.record().
    // No exponemos el ledger en si, solo el resultado de la comparacion, asi
    // que la GUI no necesita saber nada de como esta sincronizado por dentro.
    public boolean invariantOk() {
        int scoreSum = adventurers.stream().mapToInt(Adventurer::score).sum();
        int ledgerTotal = ledger.totalCrafted();
        int eventCount = ledger.eventCount();
        return scoreSum == ledgerTotal && ledgerTotal == eventCount;
    }

    public GameEngine(GameConfig config) {
        this.config = config;
        this.stations = createStations(config.stations());
        this.roundStart = new CyclicBarrier(config.adventurers() + 1);
        this.roundEnd = new CyclicBarrier(config.adventurers() + 1);

        for (int i = 1; i <= config.adventurers(); i++) {
            adventurers.add(new Adventurer(
                    i,
                    stations,
                    ledger,
                    roundStart,
                    roundEnd,
                    config.rounds()));
        }
    }

    /** Pausa el avance de rondas. Los adventurers quedan bloqueados en la barrera de forma segura. */
    public void pauseGame() {
        paused = true;
    }

    /** Reanuda el avance de rondas si estaba en pausa. */
    public void resumeGame() {
        synchronized (pauseLock) {
            paused = false;
            pauseLock.notifyAll();
        }
    }

    /** Detiene el juego por completo. Interrumpe a todos los adventurers de forma controlada. */
    public void stopGame() {
        stopRequested = true;
        resumeGame(); // por si estaba en pausa, lo despierta para que note el stop
        for (Adventurer a : adventurers) {
            a.interrupt();
        }
    }

    private void waitWhilePaused() throws InterruptedException {
        synchronized (pauseLock) {
            while (paused && !stopRequested) {
                pauseLock.wait();
            }
        }
    }

    public void run() throws InterruptedException, BrokenBarrierException {
        startDeadlockWatchdog();
        adventurers.forEach(Thread::start);

        for (int round = 1; round <= config.rounds(); round++) {
            if (stopRequested) {
                break;
            }
            waitWhilePaused();
            if (stopRequested) {
                break;
            }

            currentRound = round;

            roundStart.await();
            roundEnd.await();

            lastInvariantOk = invariantOk();
            printRoundSnapshot(round);

            if (roundDelayMs > 0) {
                Thread.sleep(roundDelayMs);
            }
        }

        if (stopRequested) {
            for (Adventurer adventurer : adventurers) {
                adventurer.interrupt();
            }
        }

        for (Adventurer adventurer : adventurers) {
            adventurer.join();
        }

        finished.set(true);
        printFinalSummary();
    }

    private void startDeadlockWatchdog() {
        Thread watchdog = new Thread(() -> {
            ThreadMXBean bean = ManagementFactory.getThreadMXBean();
            while (!finished.get()) {
                long[] ids = bean.findDeadlockedThreads();
                if (ids != null && ids.length > 0) {
                    System.err.println("\n*** DEADLOCK DETECTED BY GAME WATCHDOG ***");
                    System.err.println("Run DeadlockProbe or jcmd <PID> Thread.print for a focused diagnosis.");
                    System.err.println("The starter exits here so you do not have to kill a frozen process manually.\n");
                    System.exit(2);
                }
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }, "deadlock-watchdog");
        watchdog.setDaemon(true);
        watchdog.start();
    }

    private void printRoundSnapshot(int round) {
        int scoreSum = adventurers.stream().mapToInt(Adventurer::score).sum();
        int ledgerTotal = ledger.totalCrafted();
        int eventCount = ledger.eventCount();

        System.out.printf(
                "ROUND %02d | scoreSum=%d | ledger=%d | events=%d | invariant=%s%n",
                round,
                scoreSum,
                ledgerTotal,
                eventCount,
                (scoreSum == ledgerTotal && ledgerTotal == eventCount) ? "OK" : "BROKEN");
    }

    private void printFinalSummary() {
        System.out.println("\n=== RELIC RUSH - FINAL SCORE ===");
        adventurers.stream()
                .sorted(Comparator.comparingInt(Adventurer::score).reversed())
                .forEach(a -> System.out.printf("%-16s %4d relics%n", a.getName(), a.score()));

        int scoreSum = adventurers.stream().mapToInt(Adventurer::score).sum();
        System.out.printf("Total by players : %d%n", scoreSum);
        System.out.printf("Ledger total     : %d%n", ledger.totalCrafted());
        System.out.printf("Ledger events    : %d%n", ledger.eventCount());
    }

    private static List<ForgeStation> createStations(int count) {
        String[] names = {
                "Arcane Anvil", "Crystal Lens", "Rune Press", "Dragon Furnace",
                "Moon Altar", "Obsidian Table", "Echo Forge", "Solar Crucible"
        };
        List<ForgeStation> result = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            result.add(new ForgeStation(i + 1, names[i % names.length] + " " + (i + 1)));
        }
        return List.copyOf(result);
    }

}