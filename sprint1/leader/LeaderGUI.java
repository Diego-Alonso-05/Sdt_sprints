package leader;

import common.IPFSPubSub;

import javax.swing.*;
import java.awt.*;

public class LeaderGUI extends JFrame {

    private static final String PUBSUB_TOPIC = "leader-events";

    private JTextArea logArea;
    private JButton startServerBtn;
    private JButton sendMsgBtn;
    private JTextField msgField;

    private TCPServer server;
    private boolean serverRunning = false;

    public LeaderGUI() {
        super("Leader");

        setSize(700, 450);
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setLayout(new BorderLayout());

        // === Área de logs ===
        logArea = new JTextArea();
        logArea.setEditable(false);
        logArea.setFont(new Font("Consolas", Font.PLAIN, 14));
        add(new JScrollPane(logArea), BorderLayout.CENTER);

        // === Panel inferior ===
        JPanel bottom = new JPanel(new BorderLayout());

        JPanel leftButtons = new JPanel(new FlowLayout(FlowLayout.LEFT));
        startServerBtn = new JButton("Encender servidor TCP");
        sendMsgBtn = new JButton("Enviar mensaje a peers");
        sendMsgBtn.setEnabled(false);

        leftButtons.add(startServerBtn);
        leftButtons.add(sendMsgBtn);

        msgField = new JTextField();
        msgField.setPreferredSize(new Dimension(300, 30));

        bottom.add(leftButtons, BorderLayout.WEST);
        bottom.add(msgField, BorderLayout.CENTER);

        add(bottom, BorderLayout.SOUTH);

        // === Actions ===
        startServerBtn.addActionListener(e -> startServer());
        sendMsgBtn.addActionListener(e -> sendMessage());

        setLocationRelativeTo(null);
        setVisible(true);
    }

    private void startServer() {
        if (serverRunning) {
            log("El servidor ya está encendido.");
            return;
        }

        try {
            server = new TCPServer(5000, this, PUBSUB_TOPIC);
            server.start();

            serverRunning = true;
            startServerBtn.setEnabled(false);
            sendMsgBtn.setEnabled(true);

            log("Servidor TCP escuchando en puerto 5000");
        } catch (Exception ex) {
            log("Error arrancando servidor: " + ex.getMessage());
        }
    }

    private void sendMessage() {
    if (!serverRunning) {
        log("Enciende el servidor primero.");
        return;
    }

    String msg = msgField.getText().trim();
    if (msg.isEmpty()) {
        log("No puedes enviar mensajes vacíos.");
        return;
    }

    String payload = "MENSAJE|" + msg;

    try {
        IPFSPubSub.publish(PUBSUB_TOPIC, payload);
        log("Enviado → " + payload);
        msgField.setText("");
    } catch (Exception e) {
        log("Error → " + e.getMessage());
    }
}



    public void log(String txt) {
        logArea.append(txt + "\n");
        logArea.setCaretPosition(logArea.getDocument().getLength());
        System.out.println(txt);
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(LeaderGUI::new);
    }
}
