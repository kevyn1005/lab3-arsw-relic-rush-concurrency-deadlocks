package edu.eci.arsw.relicrush.gui;

import edu.eci.arsw.relicrush.game.Adventurer;
import edu.eci.arsw.relicrush.game.GameConfig;
import edu.eci.arsw.relicrush.game.GameEngine;

public class GameController {

    private GameEngine engine;
    private Thread engineThread;


    public void start(GameConfig config) {
        Adventurer.visualDelayMs = 400; // solo para visualización en GUI

        engine = new GameEngine(config);

        engineThread = new Thread(() -> {
            try {
                engine.run();
            } catch (InterruptedException | java.util.concurrent.BrokenBarrierException e) {
                Thread.currentThread().interrupt();
            }
        }, "game-engine-thread");

        engineThread.start();
    }

    public GameEngine engine() {
        return engine;
    }
}