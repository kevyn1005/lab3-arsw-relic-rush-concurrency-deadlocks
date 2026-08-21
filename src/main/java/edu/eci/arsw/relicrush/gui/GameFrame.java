package edu.eci.arsw.relicrush.gui;

import edu.eci.arsw.relicrush.game.GameConfig;

import javax.swing.*;
import java.awt.*;

public class GameFrame extends JFrame {

    private final BoardPanel board;
    private final GameController controller = new GameController();

    public GameFrame() {
        setTitle("Relic Rush");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);

        board = new BoardPanel();
        board.setController(controller);
        board.setBounds(0, 0, 696, 750);

        JButton startButton = new JButton("Start");
        startButton.setBounds(20, 20, 100, 35); // x, y, ancho, alto — arriba a la izquierda
        startButton.addActionListener(e -> {
            controller.start(GameConfig.defaults());
            startButton.setEnabled(false);
        });

        JLayeredPane layeredPane = new JLayeredPane();
        layeredPane.setPreferredSize(new Dimension(696, 995));
        layeredPane.add(board, Integer.valueOf(0));          // capa de abajo: el fondo
        layeredPane.add(startButton, Integer.valueOf(1));    // capa de arriba: el botón

        add(layeredPane);

        pack();
        setResizable(false);
        setLocationRelativeTo(null);
        setVisible(true);
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(GameFrame::new);
    }
}