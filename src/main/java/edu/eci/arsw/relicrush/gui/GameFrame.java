package edu.eci.arsw.relicrush.gui;

import edu.eci.arsw.relicrush.game.GameConfig;

import javax.swing.*;
import java.awt.*;
import java.net.URL;

public class GameFrame extends JFrame {

    private final BoardPanel board;
    private final GameController controller = new GameController();

    private static final int BUTTON_SIZE = 45;

    public GameFrame() {
        setTitle("Relic Rush");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);

        board = new BoardPanel();
        board.setController(controller);
        board.setBounds(0, 0, 696, 750);

        JButton startButton = iconButton("/Sprites_Lab3/buttons/start.png");
        JButton pauseButton = iconButton("/Sprites_Lab3/buttons/pause.png");
        JButton resumeButton = iconButton("/Sprites_Lab3/buttons/resume.png");
        JButton stopButton = iconButton("/Sprites_Lab3/buttons/stop.png");

        int x = 20;
        int y = 20;
        int gap = 10;
        startButton.setBounds(x, y, BUTTON_SIZE, BUTTON_SIZE);
        pauseButton.setBounds(x + (BUTTON_SIZE + gap), y, BUTTON_SIZE, BUTTON_SIZE);
        resumeButton.setBounds(x + 2 * (BUTTON_SIZE + gap), y, BUTTON_SIZE, BUTTON_SIZE);
        stopButton.setBounds(x + 3 * (BUTTON_SIZE + gap), y, BUTTON_SIZE, BUTTON_SIZE);

        // Estado inicial: solo se puede iniciar
        pauseButton.setEnabled(false);
        resumeButton.setEnabled(false);
        stopButton.setEnabled(false);

        startButton.addActionListener(e -> {
            controller.start(GameConfig.defaults());
            startButton.setEnabled(false);
            pauseButton.setEnabled(true);
            resumeButton.setEnabled(false);
            stopButton.setEnabled(true);
        });

        pauseButton.addActionListener(e -> {
            controller.pause();
            pauseButton.setEnabled(false);
            resumeButton.setEnabled(true);
        });

        resumeButton.addActionListener(e -> {
            controller.resume();
            resumeButton.setEnabled(false);
            pauseButton.setEnabled(true);
        });

        stopButton.addActionListener(e -> {
            controller.stop();
            startButton.setEnabled(true);
            pauseButton.setEnabled(false);
            resumeButton.setEnabled(false);
            stopButton.setEnabled(false);
        });

        // Revisa cada tanto si el motor ya termino las rondas configuradas,
        // para volver a dejar los botones como al principio. Solo lee
        // isFinished() (un AtomicBoolean con getter), no toca nada de la
        // sincronizacion del juego.
        Timer finishWatcher = new Timer(300, e -> {
            if (controller.engine() != null && controller.engine().isFinished()) {
                startButton.setEnabled(true);
                pauseButton.setEnabled(false);
                resumeButton.setEnabled(false);
                stopButton.setEnabled(false);
            }
        });
        finishWatcher.start();

        JLayeredPane layeredPane = new JLayeredPane();
        layeredPane.setPreferredSize(new Dimension(696, 995));
        layeredPane.add(board, Integer.valueOf(0));           // capa de abajo: el fondo
        layeredPane.add(startButton, Integer.valueOf(1));
        layeredPane.add(pauseButton, Integer.valueOf(1));
        layeredPane.add(resumeButton, Integer.valueOf(1));
        layeredPane.add(stopButton, Integer.valueOf(1));

        add(layeredPane);

        pack();
        setResizable(false);
        setLocationRelativeTo(null);
        setVisible(true);
    }

    private JButton iconButton(String resourcePath) {
        URL url = getClass().getResource(resourcePath);
        if (url == null) {
            throw new IllegalStateException("No se encontró " + resourcePath + " en el classpath.");
        }
        ImageIcon rawIcon = new ImageIcon(url);
        Image scaled = rawIcon.getImage().getScaledInstance(BUTTON_SIZE, BUTTON_SIZE, Image.SCALE_SMOOTH);

        JButton button = new JButton(new ImageIcon(scaled));
        button.setBorderPainted(false);
        button.setContentAreaFilled(false);
        button.setFocusPainted(false);
        button.setOpaque(false);
        return button;
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(GameFrame::new);
    }
}