import javax.swing.*;
import java.awt.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;  // ← Add this line
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;


/**
 * TCPClient
 * ----------
 * Represents a Peer (Client) node.
 * Responsibilities:
 *  - Connect/disconnect from Leader.
 *  - Send files to Leader.
 *  - Receive broadcasts and vector updates.
 *  - Confirm new vector proposals and commit updates.
 *  - Display logs and save embeddings locally.
 *  - Shows local IPFS node status.
 *
 * New behavior (Leader-coordinated version control):
 *  - Handles UPDATE_VECTOR (opcode 21)
 *  - Sends CONFIRM_VECTOR (opcode 31)
 *  - Applies COMMIT_VECTOR (opcode 22)
 */
public class TCPClient extends JFrame {

    private JTextField hostField, portField, filePathField, peerIdField;
    private JButton browseButton, sendButton, detectPeerButton, connectButton, disconnectButton;
    private JTextArea logArea;
    private static JLabel ipfsStatusLabel;
    private File fileToSend;

    private Socket socket;
    private DataInputStream in;
    private DataOutputStream out;
    private Thread listenerThread;
    private volatile boolean connected = false;

    // ------------------------------
    // NEW: Peer-side vector state
    // ------------------------------
    private long confirmedVersion = 0L;
    private List<String> confirmedVector = new ArrayList<>();
    private String confirmedHash = computeVectorHash(confirmedVector);

    private Long pendingVersion = null;
    private List<String> pendingVector = null;
    private String pendingHash = null;
    private String pendingNewCid = null;

    public TCPClient() {
        super("Peer Client → Leader");

        // --- GUI setup (unchanged) ---
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

        ipfsStatusLabel = new JLabel("IPFS: checking...");

        JPanel connectionPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        connectionPanel.add(new JLabel("Leader:"));
        connectionPanel.add(hostField);
        connectionPanel.add(new JLabel(":"));
        connectionPanel.add(portField);
        connectionPanel.add(connectButton);
        connectionPanel.add(disconnectButton);

        JPanel peerPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        peerPanel.add(new JLabel("Peer ID:"));
        peerPanel.add(peerIdField);
        peerPanel.add(detectPeerButton);

        JPanel filePanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        filePanel.add(filePathField);
        filePanel.add(browseButton);
        filePanel.add(sendButton);

        JPanel topPanel = new JPanel();
        topPanel.setLayout(new BoxLayout(topPanel, BoxLayout.Y_AXIS));
        topPanel.add(connectionPanel);
        topPanel.add(peerPanel);
        topPanel.add(filePanel);
        topPanel.add(ipfsStatusLabel);

        add(topPanel, BorderLayout.NORTH);
        add(scrollPane, BorderLayout.CENTER);

        // Button actions
        detectPeerButton.addActionListener(e -> detectPeerId());
        browseButton.addActionListener(e -> chooseFile());
        connectButton.addActionListener(e -> connectToLeader());
        disconnectButton.addActionListener(e -> disconnect());
        sendButton.addActionListener(e -> sendFile());

        setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        pack();
        setLocationRelativeTo(null);
        setVisible(true);

        detectPeerId();
    }

    /** Detects this node's Peer ID using the local IPFS API. */
    private void detectPeerId() {
        new Thread(() -> {
            try {
                URL url = new URL("http://127.0.0.1:5001/api/v0/id");
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setConnectTimeout(1500);
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
                log("Unable to detect Peer ID (start IPFS manually if needed).");
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

            // Send HELLO with Peer ID
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

    /** Listens for messages from the Leader. */
    private void listenLoop() {
        try {
            while (connected) {
                int opcode = in.read();
                if (opcode == -1) break;

                switch (opcode) {
                    case 1: log("Broadcast from Leader: " + in.readUTF()); break;
                    case 2: log("File uploaded. CID: " + in.readUTF()); break;
                    case 20: handleVectorUpdateLegacy(); break;
                    case 21: handleUpdateVector(); break; // NEW: proposal
                    case 22: handleCommitVector(); break; // NEW: commit
                    default: log("Unknown opcode: " + opcode);
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

    // ---------------------------
    // OLD HANDLER (legacy)
    // ---------------------------
    private void handleVectorUpdateLegacy() throws IOException {
        long version = in.readLong();
        int n = in.readInt();
        List<String> cidVector = new ArrayList<>(n);
        for (int i = 0; i < n; i++) cidVector.add(in.readUTF());
        String newCid = in.readUTF();
        int dim = in.readInt();
        float[] embedding = new float[dim];
        for (int i = 0; i < dim; i++) embedding[i] = in.readFloat();

        log("Vector v" + version + " (legacy) received. New CID: " + newCid + " | dims=" + dim);
        saveEmbeddingLocally(newCid, embedding);
    }

    // ---------------------------
    // NEW HANDLERS (21 / 22 / 31)
    // ---------------------------

    /**
     * Handles UPDATE_VECTOR (opcode 21) → proposal stage.
     * 1. Read version, vector, new CID, embeddings.
     * 2. If conflict detected, abort confirmation (future conflict resolution).
     * 3. Otherwise, store as pendingVector and send CONFIRM_VECTOR back.
     */
    private void handleUpdateVector() throws IOException {
        long version = in.readLong();
        int n = in.readInt();
        List<String> vector = new ArrayList<>(n);
        for (int i = 0; i < n; i++) vector.add(in.readUTF());
        String newCid = in.readUTF();
        int dim = in.readInt();
        float[] embedding = new float[dim];
        for (int i = 0; i < dim; i++) embedding[i] = in.readFloat();

        log("UPDATE_VECTOR v" + version + " received (" + n + " CIDs). New CID=" + newCid);

        // Conflict check
        if (pendingVector != null) {
            log("Conflict: another pending vector exists. Skipping confirmation for v" + version);
            return;
        }
        if (version <= confirmedVersion) {
            log("Conflict: received version " + version + " <= current confirmed " + confirmedVersion);
            return;
        }

        // Create pending version
        pendingVersion = version;
        pendingVector = vector;
        pendingHash = computeVectorHash(pendingVector);
        pendingNewCid = newCid;

        // Save embedding temporarily
        saveTempEmbedding(newCid, embedding);
        log("Temp embedding saved for pending vector v" + version + " | hash=" + pendingHash);

        // Send CONFIRM_VECTOR back to leader
        sendConfirmVector(pendingHash);
    }

    /**
     * Handles COMMIT_VECTOR (opcode 22).
     * When commit arrives, promote pendingVector → confirmedVector.
     */
    private void handleCommitVector() throws IOException {
        long version = in.readLong();
        String vectorHash = in.readUTF();
        log("COMMIT_VECTOR v" + version + " (" + vectorHash + ") received.");

        if (pendingVector == null) {
            log("No pending vector to commit (possibly already up-to-date).");
            return;
        }
        if (!vectorHash.equals(pendingHash)) {
            log("Commit hash mismatch: expected " + pendingHash + " got " + vectorHash);
            return;
        }

        // Promote pending to confirmed
        confirmedVersion = version;
        confirmedVector = pendingVector;
        confirmedHash = vectorHash;

        pendingVector = null;
        pendingHash = null;
        pendingVersion = null;
        pendingNewCid = null;

        promoteTempToFinal();
        log("Local state committed → v" + confirmedVersion);
    }

    /** Sends CONFIRM_VECTOR (opcode 31) to the Leader. */
    private void sendConfirmVector(String hash) {
        if (!connected) return;
        new Thread(() -> {
            try {
                synchronized (out) {
                    out.writeByte(31);
                    out.writeUTF(hash);
                    out.flush();
                }
                log("CONFIRM_VECTOR sent (hash=" + hash + ")");
            } catch (IOException e) {
                log("Error sending CONFIRM_VECTOR: " + e.getMessage());
            }
        }).start();
    }

    // ---------------------------
    // Local persistence helpers
    // ---------------------------

    private void saveEmbeddingLocally(String cid, float[] embedding) {
        try {
            File dir = new File("state");
            if (!dir.exists()) dir.mkdirs();
            File file = new File(dir, "embedding_" + cid + ".txt");
            try (PrintWriter writer = new PrintWriter(new FileWriter(file))) {
                for (float v : embedding) writer.print(v + ",");
            }
            log("Embedding saved: " + file.getAbsolutePath());
        } catch (Exception e) {
            log("Failed to save embedding: " + e.getMessage());
        }
    }

    private void saveTempEmbedding(String cid, float[] embedding) {
        try {
            File dir = new File("state/temp_embeddings");
            if (!dir.exists()) dir.mkdirs();
            File file = new File(dir, "embedding_" + cid + ".txt");
            try (PrintWriter writer = new PrintWriter(new FileWriter(file))) {
                for (float v : embedding) writer.print(v + ",");
            }
        } catch (Exception e) {
            log("Error saving temp embedding: " + e.getMessage());
        }
    }

    private void promoteTempToFinal() {
        if (pendingNewCid == null) return;
        try {
            File temp = new File("state/temp_embeddings/embedding_" + pendingNewCid + ".txt");
            File finalDir = new File("state/embeddings");
            if (!finalDir.exists()) finalDir.mkdirs();
            File fin = new File(finalDir, "embedding_" + pendingNewCid + ".txt");

            if (temp.exists()) {
                Files.move(temp.toPath(), fin.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                log("Temp embedding promoted to final: " + fin.getAbsolutePath());
            } else {
                log("Temp embedding not found for commit.");
            }
        } catch (Exception e) {
            log("Error promoting embedding: " + e.getMessage());
        }
    }

    // ---------------------------
    // Utility
    // ---------------------------
    private static String computeVectorHash(List<String> vector) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            String joined = String.join("|", vector);
            byte[] digest = md.digest(joined.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return "hash_err_" + vector.size();
        }
    }

    private void disconnect() {
        connected = false;
        try { if (socket != null) socket.close(); } catch (IOException ignored) {}
        connectButton.setEnabled(true);
        disconnectButton.setEnabled(false);
        log("Disconnected from Leader.");
    }

    private void chooseFile() {
        JFileChooser chooser = new JFileChooser();
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            fileToSend = chooser.getSelectedFile();
            filePathField.setText(fileToSend.getAbsolutePath());
            log("Selected: " + fileToSend.getName() + " (" + fileToSend.length() + " bytes)");
        }
    }

    private void sendFile() {
        if (!connected) { log("Not connected."); return; }
        if (fileToSend == null) { log("Select a file first."); return; }

        new Thread(() -> {
            try (FileInputStream fis = new FileInputStream(fileToSend)) {
                out.writeByte(11);
                out.writeUTF(fileToSend.getName());
                out.writeLong(fileToSend.length());
                byte[] buffer = new byte[8192];
                int bytesRead;
                while ((bytesRead = fis.read(buffer)) != -1)
                    out.write(buffer, 0, bytesRead);
                out.flush();
                log("File sent: " + fileToSend.getName());
            } catch (Exception e) {
                log("File send error: " + e.getMessage());
            }
        }, "sender").start();
    }

    private void log(String msg) {
        SwingUtilities.invokeLater(() -> {
            logArea.append(msg + "\n");
            logArea.setCaretPosition(logArea.getDocument().getLength());
        });
    }

    public static void setIpfsStatus(String status) {
        SwingUtilities.invokeLater(() -> ipfsStatusLabel.setText("IPFS: " + status));
    }
}
