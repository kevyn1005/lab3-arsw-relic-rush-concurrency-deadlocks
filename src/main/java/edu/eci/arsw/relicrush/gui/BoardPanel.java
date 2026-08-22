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

    // --- Imagenes de estado de estacion ---
    private final Image stationFreeImg;
    private final Image stationOccupiedImg;
    private final Image stationDisabledImg;

    // --- Skins de los aventureros ---
    // Cada skin es una carpeta bajo Sprites_Lab3 con dos animaciones: una de
    // espera y otra de "craftear". Para meter una skin nueva solo hay que
    // agregar la carpeta con las imagenes numeradas 1.png, 2.png, ... y una
    // linea nueva en SKIN_DEFINITIONS. El jugador N usa la skin numero
    // (N - 1) % cantidad de skins cargadas, asi que con 2 skins y 8 jugadores
    // las skins se repiten, pero apenas se agregan mas carpetas la variedad
    // crece sola sin tocar el resto del panel.
    private record SkinDefinition(
            String waitingFolder,
            int waitingCount,
            boolean pingPongWaiting,
            String collectingFolder,
            int collectingCount) {
    }

    private static final SkinDefinition[] SKIN_DEFINITIONS = {
            new SkinDefinition("/Sprites_Lab3/MagicCat/Sleeping/", 6, false,
                    "/Sprites_Lab3/MagicCat/Collecting/", 7),
            new SkinDefinition("/Sprites_Lab3/Penguin/Hiii/", 12, true,
                    "/Sprites_Lab3/Penguin/Collecting/", 19),
            new SkinDefinition("/Sprites_Lab3/Fox/Waiting/", 5, true,
                    "/Sprites_Lab3/Fox/Collecting/", 3),
            new SkinDefinition("/Sprites_Lab3/Pig/Waiting/", 5, false,
                    "/Sprites_Lab3/Pig/Collecting/", 8),
            new SkinDefinition("/Sprites_Lab3/Lion/Waiting/", 2, true,
                    "/Sprites_Lab3/Lion/Collecting/", 3),
            new SkinDefinition("/Sprites_Lab3/Dog/Waiting/", 4, false,
                    "/Sprites_Lab3/Dog/Collecting/", 3),
            // agregar aca una SkinDefinition por cada skin nueva que se sume
            // a Sprites_Lab3
    };

    private static final class LoadedSkin {
        final Image[] waitingFrames;
        final Image[] collectingFrames;
        final boolean pingPongWaiting;
        int waitFrame = 0;
        int waitDirection = 1;
        int collectFrame = 0;

        LoadedSkin(SkinDefinition def) {
            this.waitingFrames = loadFrames(def.waitingFolder(), def.waitingCount());
            this.collectingFrames = loadFrames(def.collectingFolder(), def.collectingCount());
            this.pingPongWaiting = def.pingPongWaiting();
        }

        void advance() {
            collectFrame = (collectFrame + 1) % collectingFrames.length;
            if (pingPongWaiting) {
                waitFrame += waitDirection;
                if (waitFrame >= waitingFrames.length - 1) {
                    waitFrame = waitingFrames.length - 1;
                    waitDirection = -1;
                } else if (waitFrame <= 0) {
                    waitFrame = 0;
                    waitDirection = 1;
                }
            } else {
                waitFrame = (waitFrame + 1) % waitingFrames.length;
            }
        }
    }

    private final LoadedSkin[] skins;

    private static final int ANIMATION_DELAY_MS = 100;
    private static final int SPRITE_SIZE = 60;
    private static final int SPRITE_HALF = SPRITE_SIZE / 2;

    private static final int SLEEP_ZONE_X = 120;
    private static final int SLEEP_ZONE_Y = 580;

    // Esquina superior izquierda: es la unica zona del tablero que queda
    // libre de estaciones y de las posiciones de cola (la estacion 0 empieza
    // en x=120, la esquina de cola de arriba-izquierda esta en y=215). Con
    // MAX_SCORE_ROWS fijo el alto del cuadro nunca cambia, asi que no importa
    // cuantos jugadores haya, nunca se mete en esa zona.
    private static final int SCORE_PANEL_X = 10;
    private static final int SCORE_PANEL_Y = 55;
    private static final int SCORE_PANEL_WIDTH = 108;
    private static final int SCORE_ROW_HEIGHT = 16;
    private static final int MAX_SCORE_ROWS = 8;

    private static final Point[] QUEUE_CORNER_POSITIONS = {
            new Point(42, 365),
            new Point(585, 365),
            new Point(5, 215),
            new Point(640, 215),
    };

    private GameController controller;

    private static final Point[] STATION_POSITIONS = {
            new Point(120, 100),
            new Point(250, 80),
            new Point(375, 80),
            new Point(520, 110),
            new Point(120, 315),
            new Point(252, 335),
            new Point(380, 335),
            new Point(500, 310),
    };

    private static final Point BOARD_CENTER = new Point(345, 226);
    private static final int SPRITE_OFFSET = 26;

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

        stationFreeImg = loadImage("/Sprites_Lab3/posters/Libre.png");
        stationOccupiedImg = loadImage("/Sprites_Lab3/posters/Ocupado.png");
        stationDisabledImg = loadImage("/Sprites_Lab3/posters/No_Disponible.png");

        skins = new LoadedSkin[SKIN_DEFINITIONS.length];
        for (int i = 0; i < SKIN_DEFINITIONS.length; i++) {
            skins[i] = new LoadedSkin(SKIN_DEFINITIONS[i]);
        }

        setPreferredSize(new Dimension(696, 995));

        Timer animationTimer = new Timer(ANIMATION_DELAY_MS, e -> {
            for (LoadedSkin skin : skins) {
                skin.advance();
            }
            repaint();
        });
        animationTimer.start();
    }

    public void setController(GameController controller) {
        this.controller = controller;
    }

    private static Image[] loadFrames(String basePath, int count) {
        Image[] frames = new Image[count];
        for (int i = 0; i < count; i++) {
            frames[i] = loadImage(basePath + (i + 1) + ".png");
        }
        return frames;
    }

    private static Image loadImage(String path) {
        URL url = BoardPanel.class.getResource(path);
        if (url == null) {
            throw new IllegalStateException("No se encontro " + path + " en el classpath.");
        }
        return new ImageIcon(url).getImage();
    }

    private LoadedSkin skinFor(int playerId) {
        int index = (playerId - 1) % skins.length;
        return skins[index];
    }

    private Image waitingFrameFor(int playerId) {
        LoadedSkin skin = skinFor(playerId);
        return skin.waitingFrames[skin.waitFrame];
    }

    private Image collectingFrameFor(int playerId) {
        LoadedSkin skin = skinFor(playerId);
        return skin.collectingFrames[skin.collectFrame];
    }

    private Image sleepZoneFrameFor(int playerId) {
        // En la sleep zone usamos la misma animacion de espera de cada skin
        return waitingFrameFor(playerId);
    }

    private Point stationCenter(int index) {
        Point p = STATION_POSITIONS[index];
        return new Point(p.x + SPRITE_HALF, p.y + SPRITE_HALF);
    }

    private Point spriteAnchorNear(int stationIndex) {
        Point stationC = stationCenter(stationIndex);
        double dx = stationC.x - BOARD_CENTER.x;
        double dy = stationC.y - BOARD_CENTER.y;
        double len = Math.sqrt(dx * dx + dy * dy);
        if (len < 1) len = 1;
        double ux = dx / len;
        double uy = dy / len;

        int cx = (int) (stationC.x + ux * SPRITE_OFFSET);
        int cy = (int) (stationC.y + uy * SPRITE_OFFSET);
        return new Point(cx - SPRITE_HALF, cy - SPRITE_HALF);
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

    private void drawScoreboard(Graphics2D g2, List<Adventurer> adventurers) {
        boolean truncated = adventurers.size() > MAX_SCORE_ROWS;
        int rowsToList = truncated ? MAX_SCORE_ROWS - 1 : adventurers.size();
        int totalRows = truncated ? MAX_SCORE_ROWS : rowsToList;
        int height = 24 + totalRows * SCORE_ROW_HEIGHT;

        g2.setColor(new Color(0, 0, 0, 150));
        g2.fillRoundRect(SCORE_PANEL_X, SCORE_PANEL_Y, SCORE_PANEL_WIDTH, height, 12, 12);

        g2.setFont(new Font("Arial", Font.BOLD, 12));
        g2.setColor(Color.WHITE);
        g2.drawString("Puntajes", SCORE_PANEL_X + 10, SCORE_PANEL_Y + 16);

        g2.setFont(new Font("Arial", Font.PLAIN, 11));
        int row = SCORE_PANEL_Y + 16 + SCORE_ROW_HEIGHT;
        for (int i = 0; i < rowsToList; i++) {
            Adventurer a = adventurers.get(i);
            g2.setColor(PLAYER_COLORS[(a.playerId() - 1) % PLAYER_COLORS.length]);
            g2.drawString("P" + a.playerId() + ": " + a.score(), SCORE_PANEL_X + 10, row);
            row += SCORE_ROW_HEIGHT;
        }
        if (truncated) {
            g2.setColor(Color.LIGHT_GRAY);
            g2.drawString("+" + (adventurers.size() - rowsToList) + " mas", SCORE_PANEL_X + 10, row);
        }
    }

    private void drawFinishedBanner(Graphics2D g2, boolean invariantOk) {
        String message = "SIMULACION TERMINADA - invariante " + (invariantOk ? "OK" : "ROTO");
        g2.setFont(new Font("Arial", Font.BOLD, 20));
        FontMetrics metrics = g2.getFontMetrics();
        int textWidth = metrics.stringWidth(message);
        int x = (getWidth() - textWidth) / 2;
        int y = 65;

        g2.setColor(new Color(0, 0, 0, 170));
        g2.fillRoundRect(x - 12, y - 24, textWidth + 24, 32, 10, 10);

        g2.setColor(invariantOk ? new Color(90, 230, 120) : new Color(235, 70, 70));
        g2.drawString(message, x, y);
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

            Image statusImg;
            if (allStations == null || i >= allStations.size()) {
                statusImg = stationDisabledImg;
            } else {
                statusImg = allStations.get(i).isOccupied() ? stationOccupiedImg : stationFreeImg;
            }

            int posterOffsetY = 30;
            g2.drawImage(statusImg, p.x, p.y + posterOffsetY, SPRITE_SIZE, SPRITE_SIZE, this);
        }

        g2.setColor(Color.BLACK);

        if (!gameActive) {
            return;
        }

        int round = controller.engine().currentRound();
        g2.setFont(new Font("Arial", Font.BOLD, 22));
        g2.setColor(Color.WHITE);
        g2.drawString("ROUND " + round, getWidth() / 2 - 50, 30);

        List<Adventurer> adventurers = controller.engine().adventurers();
        drawScoreboard(g2, adventurers);

        int sleepX = SLEEP_ZONE_X;
        int sleepY = SLEEP_ZONE_Y;
        int queueCornerIndex = 0;

        for (Adventurer a : adventurers) {
            switch (a.visualState()) {
                case DONE_WAITING_BARRIER -> {
                    g2.drawImage(sleepZoneFrameFor(a.playerId()), sleepX, sleepY, SPRITE_SIZE, SPRITE_SIZE, this);
                    sleepX += SPRITE_SIZE + 5;
                }
                case WAITING_FOR_STATION -> {
                    Point p = QUEUE_CORNER_POSITIONS[queueCornerIndex % QUEUE_CORNER_POSITIONS.length];
                    g2.drawImage(waitingFrameFor(a.playerId()), p.x, p.y, SPRITE_SIZE, SPRITE_SIZE, this);
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

                    Point spriteTopLeft = spriteAnchorNear(nearIndex);
                    Point spriteCenter = new Point(spriteTopLeft.x + SPRITE_HALF, spriteTopLeft.y + SPRITE_HALF);
                    Point farCenter = stationCenter(farIndex);

                    drawMagicCurve(g2, spriteCenter, farCenter, color);

                    g2.drawImage(collectingFrameFor(a.playerId()), spriteTopLeft.x, spriteTopLeft.y,
                            SPRITE_SIZE, SPRITE_SIZE, this);
                    g2.setColor(Color.WHITE);
                    g2.drawString("P" + a.playerId(), spriteTopLeft.x, spriteTopLeft.y + SPRITE_SIZE + 12);
                }
            }
        }

        if (controller.engine().isFinished()) {
            drawFinishedBanner(g2, controller.engine().invariantOk());
        }
    }
}
