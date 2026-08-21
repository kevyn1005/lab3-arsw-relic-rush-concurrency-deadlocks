package edu.eci.arsw.relicrush.gui;

import edu.eci.arsw.relicrush.game.Adventurer;
import edu.eci.arsw.relicrush.game.GameConfig;
import edu.eci.arsw.relicrush.game.GameEngine;

public class GameController {

    private GameEngine engine;
    private Thread engineThread;


    public void start(GameConfig config) {
        Adventurer.visualDelayMs = 1200;   // 1.2 segundos craftando por estación
        GameEngine.roundDelayMs = 2000;    // 2 segundos de pausa entre rondas

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

    public void pause() {
        if (engine != null) {
            engine.pauseGame();
        }
    }

    public void resume() {
        if (engine != null) {
            engine.resumeGame();
        }
    }

    public void stop() {
        if (engine != null) {
            engine.stopGame();
        }
    }

    public GameEngine engine() {
        return engine;
    }
}