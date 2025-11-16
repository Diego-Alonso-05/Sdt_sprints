package peer;

import common.IPFSPubSub;

import javax.swing.*;
import java.awt.*;

public class PeerGUI extends JFrame {

    private static final String PUBSUB_TOPIC = "leader-events";
    private JTextArea logArea;

    public PeerGUI() {
        super("Peer - Sistema Distribuido");

        setSize(500, 350);
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setLayout(new BorderLayout());

        // Área de logs
        logArea = new JTextArea();
        logArea.setEditable(false);
        logArea.setFont(new Font("Consolas", Font.PLAIN, 13));
        add(new JScrollPane(logArea), BorderLayout.CENTER);

        // Iniciar la suscripción
        startSubscription();

        log("Peer escuchando en topic: " + PUBSUB_TOPIC);

        setLocationRelativeTo(null);
        setVisible(true);
    }

    private void startSubscription() {
        IPFSPubSub.subscribe(PUBSUB_TOPIC, line -> {
            SwingUtilities.invokeLater(() -> {

                String[] parts = line.split("\\|");

                switch (parts[0]) {

                    case "MENSAJE":
                        log("Mensaje del líder → " + parts[1]);
                        break;

                    case "ARCHIVO":
                        log("Archivo nuevo → " + parts[1] + " (CID: " + parts[2] + ")");
                        break;

                    default:
                        log("Mensaje desconocido → " + line);
                }
            });
        });
    }

    public void log(String msg) {
        logArea.append(msg + "\n");
        logArea.setCaretPosition(logArea.getDocument().getLength());
        System.out.println(msg);
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(PeerGUI::new);
    }
}
