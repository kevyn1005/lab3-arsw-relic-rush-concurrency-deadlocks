package edu.eci.arsw.relicrush.gui;

import edu.eci.arsw.relicrush.game.Adventurer;
import edu.eci.arsw.relicrush.model.ForgeStation;

import javax.swing.*;
import java.awt.*;
import java.net.URL;
import java.util.List;

public class BoardPanel extends JPanel {

    private final Image background;
    private final Image[] sleepingFrames;
    private final Image[] collectingFrames;

    private int sleepingFrame = 0;
    private int collectingFrame = 0;

    private static final int SLEEPING_FRAME_COUNT = 6;
    private static final int COLLECTING_FRAME_COUNT = 7;
    private static final int ANIMATION_DELAY_MS = 200;

    private static final int CAT_SIZE = 60;

    // Zona donde se "apilan" los gatos que ya terminaron y esperan
    private static final int SLEEP_ZONE_X = 100;
    private static final int SLEEP_ZONE_Y = 560;

    // Zona de "cola" para los que aún no tienen ambos locks confirmados
    private static final int QUEUE_ZONE_X = 30;
    private static final int QUEUE_ZONE_Y = 30;

    private GameController controller; // aún puede ser null si el juego no ha iniciado

    private static final java.awt.Point[] STATION_POSITIONS = {
            new java.awt.Point(90, 130),
            new java.awt.Point(250, 90),
            new java.awt.Point(400, 100),
            new java.awt.Point(560, 130),
            new java.awt.Point(120, 380),
            new java.awt.Point(280, 430),
            new java.awt.Point(420, 430),
            new java.awt.Point(560, 380),
    };

    public BoardPanel() {
        background = loadImage("/Fondo.jpg");

        sleepingFrames = new Image[SLEEPING_FRAME_COUNT];
        for (int i = 0; i < SLEEPING_FRAME_COUNT; i++) {
            sleepingFrames[i] = loadImage("/Sprites_Lab3/Sleeping/" + (i + 1) + ".png");
        }

        collectingFrames = new Image[COLLECTING_FRAME_COUNT];
        for (int i = 0; i < COLLECTING_FRAME_COUNT; i++) {
            collectingFrames[i] = loadImage("/Sprites_Lab3/Collecting/" + (i + 1) + ".png");
        }

        setPreferredSize(new Dimension(696, 995));

        Timer animationTimer = new Timer(ANIMATION_DELAY_MS, e -> {
            sleepingFrame = (sleepingFrame + 1) % SLEEPING_FRAME_COUNT;
            collectingFrame = (collectingFrame + 1) % COLLECTING_FRAME_COUNT;
            repaint();
        });
        animationTimer.start();
    }

    public void setController(GameController controller) {
        this.controller = controller;
    }

    private Image loadImage(String path) {
        URL url = getClass().getResource(path);
        if (url == null) {
            throw new IllegalStateException("No se encontró " + path + " en el classpath.");
        }
        return new ImageIcon(url).getImage();
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        g.drawImage(background, 0, 0, getWidth(), getHeight(), this);

        // --- DEBUG: dibuja las 8 zonas de estación en colores distintos ---
        Color[] debugColors = {
                Color.RED, Color.ORANGE, Color.YELLOW, Color.GREEN,
                Color.CYAN, Color.BLUE, Color.MAGENTA, Color.PINK
        };
        g.setFont(new Font("Arial", Font.BOLD, 12));
        for (int i = 0; i < STATION_POSITIONS.length; i++) {
            java.awt.Point p = STATION_POSITIONS[i];
            Color c = debugColors[i % debugColors.length];
            g.setColor(new Color(c.getRed(), c.getGreen(), c.getBlue(), 120));
            g.fillRect(p.x, p.y, CAT_SIZE, CAT_SIZE);
            g.setColor(Color.WHITE);
            g.drawString("E" + (i + 1), p.x + 5, p.y + 15);
        }

        g.setColor(new Color(255, 255, 255, 100));
        g.fillRect(SLEEP_ZONE_X, SLEEP_ZONE_Y, 300, CAT_SIZE);
        g.setColor(Color.BLACK);
        g.drawString("SLEEP ZONE", SLEEP_ZONE_X, SLEEP_ZONE_Y - 5);
        // --- FIN DEBUG ---

        if (controller == null || controller.engine() == null) {
            return;
        }

        List<Adventurer> adventurers = controller.engine().adventurers();

        int sleepX = SLEEP_ZONE_X;
        int sleepY = SLEEP_ZONE_Y;
        int queueX = QUEUE_ZONE_X;
        int queueY = QUEUE_ZONE_Y;

        for (Adventurer a : adventurers) {
            switch (a.visualState()) {
                case DONE_WAITING_BARRIER -> {
                    g.drawImage(sleepingFrames[sleepingFrame], sleepX, sleepY, CAT_SIZE, CAT_SIZE, this);
                    sleepX += CAT_SIZE + 5;
                }
                case CRAFTING -> {
                    ForgeStation first = a.currentFirstStation();
                    ForgeStation second = a.currentSecondStation();

                    if (first != null) {
                        Point p1 = STATION_POSITIONS[(first.id() - 1) % STATION_POSITIONS.length];
                        g.drawImage(collectingFrames[collectingFrame], p1.x, p1.y, CAT_SIZE, CAT_SIZE, this);
                        g.setColor(Color.WHITE);
                        g.drawString("P" + a.playerId(), p1.x, p1.y + CAT_SIZE + 12);
                    }
                    if (second != null) {
                        Point p2 = STATION_POSITIONS[(second.id() - 1) % STATION_POSITIONS.length];
                        g.drawImage(collectingFrames[collectingFrame], p2.x, p2.y, CAT_SIZE, CAT_SIZE, this);
                        g.setColor(Color.WHITE);
                        g.drawString("P" + a.playerId(), p2.x, p2.y + CAT_SIZE + 12);
                    }
                }
                case WAITING_FOR_STATION -> {
                    g.drawImage(collectingFrames[collectingFrame], queueX, queueY, CAT_SIZE / 2, CAT_SIZE / 2, this);
                    queueX += CAT_SIZE / 2 + 3;
                }
            }
        }
    }
}