package cliente;

import javax.swing.*;
import java.awt.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.ArrayList;

/**
 * TCPClient
 * ----------
 * Represents a peer (client) that connects to the Leader server.
 * Responsibilities:
 *  - Connect/disconnect from the Leader.
 *  - Send files to the Leader.
 *  - Receive broadcasts and vector updates.
 *  - Display logs and save vector/embedding data locally.
 */
public class TCPCliente extends JFrame {

    private JTextField hostField, portField, filePathField, peerIdField;
    private JButton browseButton, sendButton, detectPeerButton, connectButton, disconnectButton;
    private JTextArea logArea;
    private File fileToSend;

    private Socket socket;
    private DataInputStream in;
    private DataOutputStream out;
    private Thread listenerThread;
    private volatile boolean connected = false;

    public TCPCliente() {
        super("Peer Client → Leader");

        // UI fields
        hostField = new JTextField("127.0.0.1", 10);
        portField = new JTextField("5000", 5);
        peerIdField = new JTextField(30);
        peerIdField.setText("Unknown");

        browseButton = new JButton("Select File");
        sendButton = new JButton("Send");
        detectPeerButton = new JButton("Detect Peer ID");
        connectButton = new JButton("Connect");
        disconnectButton = new JButton("Disconnect");
        disconnectButton.setEnabled(false);

        filePathField = new JTextField(25);
        filePathField.setEditable(false);

        logArea = new JTextArea(18, 60);
        logArea.setEditable(false);
        JScrollPane scrollPane = new JScrollPane(logArea);

        // Connection panel
        JPanel connectionPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        connectionPanel.add(new JLabel("Leader:"));
        connectionPanel.add(hostField);
        connectionPanel.add(new JLabel(":"));
        connectionPanel.add(portField);
        connectionPanel.add(connectButton);
        connectionPanel.add(disconnectButton);

        // Peer ID panel
        JPanel peerPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        peerPanel.add(new JLabel("Peer ID:"));
        peerPanel.add(peerIdField);
        peerPanel.add(detectPeerButton);

        // File panel
        JPanel filePanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        filePanel.add(filePathField);
        filePanel.add(browseButton);
        filePanel.add(sendButton);

        // Combine panels
        setLayout(new BorderLayout());
        JPanel topPanel = new JPanel();
        topPanel.setLayout(new BoxLayout(topPanel, BoxLayout.Y_AXIS));
        topPanel.add(connectionPanel);
        topPanel.add(peerPanel);
        topPanel.add(filePanel);

        add(topPanel, BorderLayout.NORTH);
        add(scrollPane, BorderLayout.CENTER);

        // Button listeners
        detectPeerButton.addActionListener(e -> detectPeerId());
        browseButton.addActionListener(e -> chooseFile());
        connectButton.addActionListener(e -> connectToLeader());
        disconnectButton.addActionListener(e -> disconnect());
        sendButton.addActionListener(e -> sendFile());

        setDefaultCloseOperation(EXIT_ON_CLOSE);
        pack();
        setLocationRelativeTo(null);
        setVisible(true);

        detectPeerId(); // Attempt to auto-detect Peer ID at startup
    }

    /** Detects this node's Peer ID using the local IPFS API. */
    private void detectPeerId() {
        new Thread(() -> {
            try {
                URL url = new URL("http://127.0.0.1:5001/api/v0/id");
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setConnectTimeout(1000);
                conn.setReadTimeout(1500);

                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {

                    StringBuilder response = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) response.append(line);
                    String json = response.toString();
                    int i = json.indexOf("\"ID\":\"");
                    if (i != -1) {
                        int j = json.indexOf("\"", i + 6);
                        String id = json.substring(i + 6, j);
                        peerIdField.setText(id);
                        log("Peer ID detected: " + id);
                        return;
                    }
                }
            } catch (Exception e) {
                log("Unable to detect Peer ID (enter manually if needed).");
            }
        }).start();
    }

    /** Connects this client to the Leader server. */
    private void connectToLeader() {
        if (connected) return;
        String host = hostField.getText().trim();
        int port = Integer.parseInt(portField.getText().trim());
        try {
            socket = new Socket(host, port);
            in = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
            out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
            connected = true;
            connectButton.setEnabled(false);
            disconnectButton.setEnabled(true);
            log("Connected to Leader.");

            // Send HELLO message with Peer ID
            String peerID = peerIdField.getText().trim();
            out.writeByte(10);
            out.writeUTF(peerID);
            out.flush();

            listenerThread = new Thread(this::listenLoop, "listener");
            listenerThread.start();
        } catch (Exception e) {
            log("Connection error: " + e.getMessage());
        }
    }

    /** Listens for messages from the Leader (runs in a background thread). */
    private void listenLoop() {
        try {
            while (connected) {
                int opcode = in.read();
                if (opcode == -1) break;

                switch (opcode) {
                    case 1: { // Broadcast message from Leader
                        String msg = in.readUTF();
                        log("Broadcast from Leader: " + msg);
                        break;
                    }
                    case 2: { // CID response from Leader
                        String cid = in.readUTF();
                        log("File successfully uploaded. CID: " + cid);
                        break;
                    }
                    case 20: { // Vector update
                        long version = in.readLong();
                        int n = in.readInt();
                        List<String> cidVector = new ArrayList<>(n);
                        for (int i = 0; i < n; i++) cidVector.add(in.readUTF());
                        String newCid = in.readUTF();
                        int dim = in.readInt();
                        float[] embedding = new float[dim];
                        for (int i = 0; i < dim; i++) embedding[i] = in.readFloat();

                        log("Vector v" + version + " received (size=" + n +
                                "). New CID: " + newCid + " | embedding dims=" + dim);
                        saveEmbeddingLocally(newCid, embedding);
                        break;
                    }
                    default:
                        log("Unknown opcode: " + opcode);
                        break;
                }
            }
        } catch (IOException e) {
            if (connected) log("Connection closed: " + e.getMessage());
        } finally {
            connected = false;
            connectButton.setEnabled(true);
            disconnectButton.setEnabled(false);
        }
    }


    /** Saves the embedding data as a local text file. */
    private void saveEmbeddingLocally(String cid, float[] embedding) {
        try {
            File dir = new File("state");
            if (!dir.exists()) dir.mkdirs();
            File file = new File(dir, "embedding_" + cid + ".txt");
            try (PrintWriter writer = new PrintWriter(new FileWriter(file))) {
                for (int i = 0; i < embedding.length; i++) {
                    if (i > 0) writer.print(",");
                    writer.print(embedding[i]);
                }
            }
            log("Embedding saved at " + file.getAbsolutePath());
        } catch (Exception e) {
            log("Unable to save embedding: " + e.getMessage());
        }
    }

    /** Disconnects this client from the Leader. */
    private void disconnect() {
        connected = false;
        try {
            if (socket != null) socket.close();
        } catch (IOException ignored) {}
        connectButton.setEnabled(true);
        disconnectButton.setEnabled(false);
        log("Disconnected from Leader.");
    }

    /** Opens a file chooser dialog for selecting the file to send. */
    private void chooseFile() {
        JFileChooser chooser = new JFileChooser();
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            fileToSend = chooser.getSelectedFile();
            filePathField.setText(fileToSend.getAbsolutePath());
            log("Selected file: " + fileToSend.getName() + " (" + fileToSend.length() + " bytes)");
        }
    }

    /** Sends the selected file to the Leader server. */
    private void sendFile() {
        if (!connected) {
            log("You are not connected to the Leader.");
            return;
        }
        if (fileToSend == null) {
            log("Please select a file first.");
            return;
        }

        new Thread(() -> {
            try (FileInputStream fis = new FileInputStream(fileToSend)) {
                out.writeByte(11); // FILE_UPLOAD opcode
                out.writeUTF(fileToSend.getName());
                out.writeLong(fileToSend.length());
                byte[] buffer = new byte[8192];
                int bytesRead;
                while ((bytesRead = fis.read(buffer)) != -1)
                    out.write(buffer, 0, bytesRead);
                out.flush();
                log("File sent to Leader: " + fileToSend.getName());
            } catch (Exception e) {
                log("Error sending file: " + e.getMessage());
            }
        }, "sender").start();
    }

    /** Thread-safe GUI logger. */
    private void log(String msg) {
        SwingUtilities.invokeLater(() -> {
            logArea.append(msg + "\n");
            logArea.setCaretPosition(logArea.getDocument().getLength());
        });
    }

    /** Main entry point to launch the Client GUI. */
    public static void main(String[] args) {
        SwingUtilities.invokeLater(TCPCliente::new);
    }
}
