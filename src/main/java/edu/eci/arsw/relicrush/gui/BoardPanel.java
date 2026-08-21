package edu.eci.arsw.relicrush.gui;

import edu.eci.arsw.relicrush.game.Adventurer;
import edu.eci.arsw.relicrush.model.ForgeStation;

import javax.swing.*;
import java.awt.*;
import java.awt.geom.QuadCurve2D;
import java.net.URL;
import java.util.List;

public class BoardPanel extends JPanel {

    private final Image background;

    // --- Gato (skin para playerId impar) ---
    private final Image[] catSleepingFrames;
    private final Image[] catCollectingFrames;
    private static final int CAT_SLEEPING_COUNT = 6;
    private static final int CAT_COLLECTING_COUNT = 7;
    private int catSleepFrame = 0;
    private int catCollectFrame = 0;

    // --- Pingüino (skin para playerId par) ---
    private final Image[] penguinWaitingFrames; // "Hiii", ping-pong 1->12->1
    private final Image[] penguinCollectingFrames; // normal, 19 frames
    private static final int PENGUIN_WAITING_COUNT = 12;
    private static final int PENGUIN_COLLECTING_COUNT = 19;
    private int penguinWaitFrame = 0;
    private int penguinWaitDirection = 1; // 1 = avanzando, -1 = retrocediendo
    private int penguinCollectFrame = 0;

    private static final int ANIMATION_DELAY_MS = 100;
    private static final int CAT_SIZE = 60;
    private static final int CAT_HALF = CAT_SIZE / 2;

    private static final int SLEEP_ZONE_X = 120;
    private static final int SLEEP_ZONE_Y = 580;

    private static final Point[] QUEUE_CORNER_POSITIONS = {
            new Point(42, 365),
            new Point(585, 365),
            new Point(5, 215),
            new Point(640, 215),
    };

    private GameController controller;

    private static final Point[] STATION_POSITIONS = {
            new Point(120, 85),
            new Point(250, 72),
            new Point(375, 72),
            new Point(520, 100),
            new Point(120, 305),
            new Point(252, 320),
            new Point(380, 320),
            new Point(500, 290),
    };

    private static final Point BOARD_CENTER = new Point(345, 226);
    private static final int CAT_OFFSET = 26;

    private static final Color STATION_FREE = new Color(70, 200, 90, 130);
    private static final Color STATION_OCCUPIED = new Color(220, 60, 60, 130);
    private static final Color STATION_DISABLED = new Color(120, 120, 120, 110);

    private static final Color[] PLAYER_COLORS = {
            new Color(80, 200, 255),
            new Color(255, 120, 80),
            new Color(140, 255, 120),
            new Color(255, 210, 60),
            new Color(220, 100, 255),
            new Color(255, 100, 160),
            new Color(100, 255, 220),
            new Color(255, 160, 200),
    };

    public BoardPanel() {
        background = loadImage("/Fondo.jpg");

        catSleepingFrames = loadFrames("/Sprites_Lab3/MagicCat/Sleeping/", CAT_SLEEPING_COUNT);
        catCollectingFrames = loadFrames("/Sprites_Lab3/MagicCat/Collecting/", CAT_COLLECTING_COUNT);

        penguinWaitingFrames = loadFrames("/Sprites_Lab3/Penguin/Hiii/", PENGUIN_WAITING_COUNT);
        penguinCollectingFrames = loadFrames("/Sprites_Lab3/Penguin/Collecting/", PENGUIN_COLLECTING_COUNT);

        setPreferredSize(new Dimension(696, 995));

        Timer animationTimer = new Timer(ANIMATION_DELAY_MS, e -> {
            catSleepFrame = (catSleepFrame + 1) % CAT_SLEEPING_COUNT;
            catCollectFrame = (catCollectFrame + 1) % CAT_COLLECTING_COUNT;
            penguinCollectFrame = (penguinCollectFrame + 1) % PENGUIN_COLLECTING_COUNT;

            penguinWaitFrame += penguinWaitDirection;
            if (penguinWaitFrame >= PENGUIN_WAITING_COUNT - 1) {
                penguinWaitFrame = PENGUIN_WAITING_COUNT - 1;
                penguinWaitDirection = -1;
            } else if (penguinWaitFrame <= 0) {
                penguinWaitFrame = 0;
                penguinWaitDirection = 1;
            }

            repaint();
        });
        animationTimer.start();
    }

    public void setController(GameController controller) {
        this.controller = controller;
    }

    private Image[] loadFrames(String basePath, int count) {
        Image[] frames = new Image[count];
        for (int i = 0; i < count; i++) {
            frames[i] = loadImage(basePath + (i + 1) + ".png");
        }
        return frames;
    }

    private Image loadImage(String path) {
        URL url = getClass().getResource(path);
        if (url == null) {
            throw new IllegalStateException("No se encontró " + path + " en el classpath.");
        }
        return new ImageIcon(url).getImage();
    }

    private boolean isCatSkin(int playerId) {
        return playerId % 2 != 0;
    }

    private Image waitingFrameFor(int playerId) {
        return isCatSkin(playerId) ? catSleepingFrames[catSleepFrame] : penguinWaitingFrames[penguinWaitFrame];
    }

    private Image collectingFrameFor(int playerId) {
        return isCatSkin(playerId) ? catCollectingFrames[catCollectFrame] : penguinCollectingFrames[penguinCollectFrame];
    }

    private Image sleepZoneFrameFor(int playerId) {
        // En la SLEEP ZONE usamos la misma animación de "espera" de cada especie
        return waitingFrameFor(playerId);
    }

    private Point stationCenter(int index) {
        Point p = STATION_POSITIONS[index];
        return new Point(p.x + CAT_HALF, p.y + CAT_HALF);
    }

    private Point catAnchorNear(int stationIndex) {
        Point stationC = stationCenter(stationIndex);
        double dx = stationC.x - BOARD_CENTER.x;
        double dy = stationC.y - BOARD_CENTER.y;
        double len = Math.sqrt(dx * dx + dy * dy);
        if (len < 1) len = 1;
        double ux = dx / len;
        double uy = dy / len;

        int cx = (int) (stationC.x + ux * CAT_OFFSET);
        int cy = (int) (stationC.y + uy * CAT_OFFSET);
        return new Point(cx - CAT_HALF, cy - CAT_HALF);
    }

    private void drawMagicCurve(Graphics2D g2, Point fromCenter, Point toCenter, Color color) {
        double mx = (fromCenter.x + toCenter.x) / 2.0;
        double my = (fromCenter.y + toCenter.y) / 2.0;

        double dx = mx - BOARD_CENTER.x;
        double dy = my - BOARD_CENTER.y;
        double len = Math.sqrt(dx * dx + dy * dy);
        if (len < 1) len = 1;
        double ux = dx / len;
        double uy = dy / len;

        double curveStrength = 40;
        double ctrlX = mx + ux * curveStrength;
        double ctrlY = my + uy * curveStrength;

        QuadCurve2D curve = new QuadCurve2D.Double(
                fromCenter.x, fromCenter.y, ctrlX, ctrlY, toCenter.x, toCenter.y);

        g2.setColor(color);
        g2.setStroke(new BasicStroke(3f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g2.draw(curve);
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        Graphics2D g2 = (Graphics2D) g;
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        g2.drawImage(background, 0, 0, getWidth(), getHeight(), this);

        boolean gameActive = controller != null && controller.engine() != null;
        List<ForgeStation> allStations = gameActive ? controller.engine().stations() : null;

        g2.setFont(new Font("Arial", Font.BOLD, 12));
        for (int i = 0; i < STATION_POSITIONS.length; i++) {
            Point p = STATION_POSITIONS[i];

            Color statusColor;
            if (allStations == null || i >= allStations.size()) {
                statusColor = STATION_DISABLED;
            } else {
                statusColor = allStations.get(i).isOccupied() ? STATION_OCCUPIED : STATION_FREE;
            }

            g2.setColor(statusColor);
            g2.fillRect(p.x, p.y, CAT_SIZE, CAT_SIZE);
            g2.setColor(Color.WHITE);
            g2.drawString("E" + (i + 1), p.x + 5, p.y + 15);
        }

        String[] cornerLabels = {"Q1", "Q2", "Q3", "Q4"};
        for (int i = 0; i < QUEUE_CORNER_POSITIONS.length; i++) {
            Point p = QUEUE_CORNER_POSITIONS[i];
            g2.setColor(new Color(255, 255, 255, 70));
            g2.fillRect(p.x, p.y, CAT_SIZE, CAT_SIZE);
            g2.setColor(Color.BLACK);
            g2.drawString(cornerLabels[i], p.x + 5, p.y + 15);
        }

        g2.setColor(new Color(255, 255, 255, 100));
        g2.fillRect(SLEEP_ZONE_X, SLEEP_ZONE_Y, 300, CAT_SIZE);
        g2.setColor(Color.BLACK);
        g2.drawString("SLEEP ZONE", SLEEP_ZONE_X, SLEEP_ZONE_Y - 5);

        if (!gameActive) {
            return;
        }

        int round = controller.engine().currentRound();
        g2.setFont(new Font("Arial", Font.BOLD, 22));
        g2.setColor(Color.WHITE);
        g2.drawString("ROUND " + round, getWidth() / 2 - 50, 30);

        List<Adventurer> adventurers = controller.engine().adventurers();
        int sleepX = SLEEP_ZONE_X;
        int sleepY = SLEEP_ZONE_Y;
        int queueCornerIndex = 0;

        for (Adventurer a : adventurers) {
            switch (a.visualState()) {
                case DONE_WAITING_BARRIER -> {
                    g2.drawImage(sleepZoneFrameFor(a.playerId()), sleepX, sleepY, CAT_SIZE, CAT_SIZE, this);
                    sleepX += CAT_SIZE + 5;
                }
                case WAITING_FOR_STATION -> {
                    Point p = QUEUE_CORNER_POSITIONS[queueCornerIndex % QUEUE_CORNER_POSITIONS.length];
                    g2.drawImage(waitingFrameFor(a.playerId()), p.x, p.y, CAT_SIZE, CAT_SIZE, this);
                    queueCornerIndex++;
                }
                case CRAFTING -> {
                    ForgeStation first = a.currentFirstStation();
                    ForgeStation second = a.currentSecondStation();
                    if (first == null || second == null) continue;

                    ForgeStation nearStation = first.id() <= second.id() ? first : second;
                    ForgeStation farStation = first.id() <= second.id() ? second : first;

                    int nearIndex = nearStation.id() - 1;
                    int farIndex = farStation.id() - 1;
                    if (nearIndex < 0 || nearIndex >= STATION_POSITIONS.length) continue;
                    if (farIndex < 0 || farIndex >= STATION_POSITIONS.length) continue;

                    Color color = PLAYER_COLORS[(a.playerId() - 1) % PLAYER_COLORS.length];

                    Point catTopLeft = catAnchorNear(nearIndex);
                    Point catCenter = new Point(catTopLeft.x + CAT_HALF, catTopLeft.y + CAT_HALF);
                    Point farCenter = stationCenter(farIndex);

                    drawMagicCurve(g2, catCenter, farCenter, color);

                    g2.drawImage(collectingFrameFor(a.playerId()), catTopLeft.x, catTopLeft.y, CAT_SIZE, CAT_SIZE, this);
                    g2.setColor(Color.WHITE);
                    g2.drawString("P" + a.playerId(), catTopLeft.x, catTopLeft.y + CAT_SIZE + 12);
                }
            }
        }
    }
}