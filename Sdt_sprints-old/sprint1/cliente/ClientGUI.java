package cliente;

import javax.swing.*;
import java.awt.*;
import java.io.File;
import java.net.Socket;

public class ClientGUI extends JFrame {

    private JTextField ipField;
    private JTextArea logArea;
    private JButton connectBtn;
    private JButton uploadBtn;
    private TCPClient client;

    public ClientGUI() {
        setTitle("💻 Cliente - Sistema Distribuido");
        setSize(500, 300);
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setLayout(new BorderLayout());

        // 🔹 Panel superior
        JPanel topPanel = new JPanel();
        topPanel.add(new JLabel("IP del líder:"));
        ipField = new JTextField("127.0.0.1", 15);
        connectBtn = new JButton("Conectar");
        uploadBtn = new JButton("Subir Archivo");
        uploadBtn.setEnabled(false); // desactivado hasta conectar

        topPanel.add(ipField);
        topPanel.add(connectBtn);
        topPanel.add(uploadBtn);
        add(topPanel, BorderLayout.NORTH);

        // 🔹 Área de log
        logArea = new JTextArea();
        logArea.setEditable(false);
        add(new JScrollPane(logArea), BorderLayout.CENTER);

        // 🔹 Acciones
        connectBtn.addActionListener(e -> connectToServer());
        uploadBtn.addActionListener(e -> uploadFile());
    }

    private void connectToServer() {
        String ip = ipField.getText().trim();
        try {
            Socket testSocket = new Socket(ip, 5000);
            testSocket.close();
            client = new TCPClient(ip, 5000);
            log("Conectado al líder en " + ip + ":5000");
            uploadBtn.setEnabled(true);
            connectBtn.setEnabled(false);
        } catch (Exception e) {
            log("No se pudo conectar al líder: " + e.getMessage());
        }
    }

    private void uploadFile() {
        JFileChooser chooser = new JFileChooser();
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            File file = chooser.getSelectedFile();
            if (client == null) {
                log("No estás conectado al servidor.");
                return;
            }
            String result = client.sendFile(file);
            log(result);
        }
    }

    private void log(String msg) {
        SwingUtilities.invokeLater(() -> logArea.append(msg + "\n"));
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> new ClientGUI().setVisible(true));
    }
}
