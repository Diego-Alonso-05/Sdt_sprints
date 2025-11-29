// TCPClient.java
// ------------------------------------------------------
// Peer-side client application.
// Communicates with the Leader using:
//   - IPFS PubSub (primary communication channel)
//   - TCP (fallback channel for file uploads and updates)
//
// UI allows selecting files and sending them to the Leader.
// Handles distributed vector updates and commits.
//
// --- Sprint 5 additions ---
// • Heartbeat support (detects when the Leader is DOWN/UP)
// • VectorIndexService async updates after commit
// ------------------------------------------------------

import common.AppLog;

import javax.swing.*;
import java.awt.*;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

public class TCPClient extends JFrame {

    // UI components
    private JTextField hostField, portField, filePathField, peerIdField;
    private JButton browseButton, sendButton, detectPeerButton, connectButton, disconnectButton;
    private JTextArea logArea;
    private static JLabel ipfsStatusLabel;
    private File fileToSend;

    // TCP connection fields
    private Socket socket;
    private DataInputStream in;
    private DataOutputStream out;
    private Thread listenerThread;
    private volatile boolean connected = false;

    // Confirmed global state (most recent committed vector)
    private long confirmedVersion = 0L;
    private List<String> confirmedVector = new ArrayList<>();
    private String confirmedHash = computeVectorHash(confirmedVector);

    // --- Sprint 5 ---
    // Heartbeat monitor for detecting Leader status
    private PeerHeartbeatMonitor heartbeatMonitor;

    // Pending proposal data (client tracks one proposal at a time)
    private Long pendingVersion = null;
    private List<String> pendingVector = null;
    private String pendingHash = null;
    private String pendingNewCid = null;
    private String pendingProposalId = null;

    // PubSub
    private static final String PUBSUB_TOPIC = "sdt-updates";
    private Thread pubsubThread;


    // -------------------------------------------------
    // Constructor
    // Sets up UI, network behavior, and IPFS daemon.
    // Starts the PubSub listener and heartbeat monitor.
    // -------------------------------------------------
    public TCPClient() {
        super("Peer Client → Leader");

        // Attempt to start local IPFS daemon (if not already running)
        try { IPFSInit.startClientDaemon(); } catch (Exception ignored) {}

        // GUI setup
        logArea = new JTextArea(18, 60);
        logArea.setEditable(false);
        JScrollPane scrollPane = new JScrollPane(logArea);

        hostField = new JTextField("127.0.0.1", 10);
        portField = new JTextField("5000", 5);
        peerIdField = new JTextField(30);
        peerIdField.setText("Unknown-" + UUID.randomUUID());

        browseButton = new JButton("Select File");
        sendButton = new JButton("Send");
        detectPeerButton = new JButton("Detect Peer ID");
        connectButton = new JButton("Connect");
        disconnectButton = new JButton("Disconnect");
        disconnectButton.setEnabled(false);

        filePathField = new JTextField(25);
        filePathField.setEditable(false);

        ipfsStatusLabel = new JLabel("IPFS: checking...");

        JPanel topPanel = new JPanel();
        topPanel.setLayout(new BoxLayout(topPanel, BoxLayout.Y_AXIS));

        // Connection UI section
        JPanel connectionPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        connectionPanel.add(new JLabel("Leader:"));
        connectionPanel.add(hostField);
        connectionPanel.add(new JLabel(":"));
        connectionPanel.add(portField);
        connectionPanel.add(connectButton);
        connectionPanel.add(disconnectButton);

        // Peer ID section
        JPanel peerPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        peerPanel.add(new JLabel("Peer ID:"));
        peerPanel.add(peerIdField);
        peerPanel.add(detectPeerButton);

        // File selection section
        JPanel filePanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        filePanel.add(filePathField);
        filePanel.add(browseButton);
        filePanel.add(sendButton);

        topPanel.add(connectionPanel);
        topPanel.add(peerPanel);
        topPanel.add(filePanel);
        topPanel.add(ipfsStatusLabel);

        add(topPanel, BorderLayout.NORTH);
        add(scrollPane, BorderLayout.CENTER);

        // Button listeners
        detectPeerButton.addActionListener(e -> detectPeerId());
        browseButton.addActionListener(e -> chooseFile());
        connectButton.addActionListener(e -> connectToLeader());
        disconnectButton.addActionListener(e -> disconnect());
        sendButton.addActionListener(e -> sendFile());

        setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        pack();
        setLocationRelativeTo(null);
        setVisible(true);

        // Redirect logs to GUI once
        AppLog.setSink(msg -> SwingUtilities.invokeLater(() -> {
            if (logArea != null) {
                logArea.append(msg + "\n");
                logArea.setCaretPosition(logArea.getDocument().getLength());
            } else {
                System.out.println(msg);
            }
        }));

        // Auto-detect PeerID at startup
        detectPeerId();

        // Start PubSub listener (receives heartbeats and vector updates)
        startPubSubListener();

        // --- Sprint 5 ---
        // Heartbeat monitor to detect Leader DOWN/UP
        heartbeatMonitor = new PeerHeartbeatMonitor(
                () -> log("Leader DOWN (no heartbeat for 25s)"),
                () -> log("Leader UP again")
        );
        heartbeatMonitor.start();
    }


    // ---------------------------------------------------------
    // Start PubSub listener
    // Runs in its own thread and pipes all messages to the GUI.
    // ---------------------------------------------------------
    private void startPubSubListener() {

        if (pubsubThread != null && pubsubThread.isAlive()) return;

        pubsubThread = new Thread(() -> {
            try {
                IPFSPubSub.subscribe(PUBSUB_TOPIC, json ->
                        SwingUtilities.invokeLater(() -> processPubSubMessage(json))
                );
                AppLog.log("PubSub listener subscribed to topic: " + PUBSUB_TOPIC);
            } catch (Exception e) {
                log("PubSub subscribe failed: " + e.getMessage());
            }
        }, "client-pubsub-listener");

        pubsubThread.setDaemon(true);
        pubsubThread.start();
    }


    // ---------------------------------------------------------
    // Process PubSub message from Leader or other peers.
    // Handles: heartbeats, UPDATE_VECTOR, COMMIT_VECTOR.
    // ---------------------------------------------------------
    private void processPubSubMessage(String json) {
        try {
            if (json == null) return;

            // Messages sometimes come inside an IPFS envelope
            String inner = tryExtractBase64Data(json);
            if (inner != null) json = inner;

            // --- Sprint 5 ---
            // Heartbeat from Leader
            if (json.contains("\"type\":\"HEARTBEAT\"")) {
                if (heartbeatMonitor != null) heartbeatMonitor.recordHeartbeat();
                return;
            }

            // Update notification
            if (json.contains("\"type\":\"UPDATE_VECTOR\"")) {
                String proposalId = extract(json, "proposalId");
                String newCid = extract(json, "newCid");
                String version = extract(json, "version");

                log("UPDATE_VECTOR (PubSub) proposal=" + proposalId +
                        " newCid=" + newCid + " v=" + version);
                return;
            }

            // Commit notification
            if (json.contains("\"type\":\"COMMIT_VECTOR\"")) {
                String proposalId = extract(json, "proposalId");
                String version = extract(json, "version");

                log("COMMIT_VECTOR (PubSub) proposal=" + proposalId + " v=" + version);

                // Only apply commit if this client participated in the proposal
                if (pendingHash != null &&
                        pendingProposalId != null &&
                        pendingProposalId.equals(proposalId)) {

                    confirmedVersion = Long.parseLong(version);
                    confirmedVector = pendingVector;
                    confirmedHash = pendingHash;

                    promoteTempToFinal(pendingNewCid);

                    // Reset pending proposal
                    pendingVersion = null;
                    pendingVector = null;
                    pendingNewCid = null;
                    pendingHash = null;
                    pendingProposalId = null;

                    // --- Sprint 5 ---
                    // Update search index asynchronously
                    VectorIndexService.getInstance()
                            .updateIndexAsync(confirmedVector, confirmedVersion);

                    log("Commit applied via PubSub. Confirmed version=" + confirmedVersion);
                }
                return;
            }

        } catch (Exception e) {
            log("PubSub message error: " + e.getMessage());
        }
    }


    // ---------------------------------------------------------
    // Attempts to decode the Base64 payload inside an IPFS
    // PubSub envelope. Returns decoded JSON or null.
    // ---------------------------------------------------------
    private String tryExtractBase64Data(String envelope) {
        try {
            int i = envelope.indexOf("\"data\":\"");
            if (i == -1) return null;

            int start = i + 8;
            int end = envelope.indexOf("\"", start);
            if (end == -1) return null;

            String b64 = envelope.substring(start, end).replace("\\", "");
            byte[] decoded = Base64.getDecoder().decode(b64);

            return new String(decoded, StandardCharsets.UTF_8).trim();
        } catch (Exception e) {
            return null;
        }
    }


    // ---------------------------------------------------------
    // Extracts a string or numeric field from simple JSON.
    // ---------------------------------------------------------
    private String extract(String json, String key) {
        try {
            // String pattern
            String pat1 = "\"" + key + "\":\"";
            int i = json.indexOf(pat1);
            if (i != -1) {
                int j = json.indexOf("\"", i + pat1.length());
                return json.substring(i + pat1.length(), j);
            }

            // Numeric pattern
            String pat2 = "\"" + key + "\":";
            i = json.indexOf(pat2);
            if (i != -1) {
                int k = i + pat2.length();
                int j = k;
                while (j < json.length() && (Character.isDigit(json.charAt(j)) || json.charAt(j) == '-'))
                    j++;
                return json.substring(k, j);
            }
        } catch (Exception ignored) {}
        return null;
    }


    // ---------------------------------------------------------
    // Detect peer's IPFS PeerID using HTTP API.
    // Updates the "Peer ID" field in the UI.
    // ---------------------------------------------------------
    private void detectPeerId() {
        new Thread(() -> {
            try {
                URL url = new URL("http://127.0.0.1:5001/api/v0/id");
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setConnectTimeout(1500);
                conn.setReadTimeout(1500);

                StringBuilder sb = new StringBuilder();
                try (BufferedReader r = new BufferedReader(
                        new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = r.readLine()) != null) sb.append(line);
                }

                String body = sb.toString();
                int i = body.indexOf("\"ID\":\"");
                if (i != -1) {
                    int j = body.indexOf("\"", i + 6);
                    String id = body.substring(i + 6, j);
                    peerIdField.setText(id);
                    log("Peer ID detected: " + id);
                    return;
                }

                log("Peer ID detection: unexpected reply: " + body);

            } catch (Exception e) {
                log("Peer ID detection failed. Using: " + peerIdField.getText());
            }
        }).start();
    }


    // ---------------------------------------------------------
    // Establish TCP connection to the Leader.
    // Sends peer ID to register itself.
    // ---------------------------------------------------------
    private void connectToLeader() {
        if (connected) return;

        try {
            socket = new Socket(
                    hostField.getText().trim(),
                    Integer.parseInt(portField.getText().trim())
            );

            in = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
            out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));

            connected = true;
            connectButton.setEnabled(false);
            disconnectButton.setEnabled(true);

            log("Connected.");

            // Introduce to Leader
            out.writeByte(10);
            out.writeUTF(peerIdField.getText());
            out.flush();

            // Start TCP listener thread
            listenerThread = new Thread(this::listenLoop, "tcp-listener");
            listenerThread.start();

        } catch (Exception e) {
            log("Connection error: " + e.getMessage());
        }
    }


    // ---------------------------------------------------------
    // Main TCP listener loop.
    // Interprets opcodes sent by the Leader.
    // ---------------------------------------------------------
    private void listenLoop() {
        try {
            while (connected) {
                int op = in.read();
                if (op == -1) break;

                switch (op) {
                    case 1: log("Broadcast: " + in.readUTF()); break;
                    case 2: log("CID response: " + in.readUTF()); break;
                    case 21: handleUpdateVector(); break;
                    case 22: handleCommitVector(); break;
                }
            }
        } catch (IOException ignored) {}
    }


    // ---------------------------------------------------------
    // Handle UPDATE_VECTOR received via TCP (fallback).
    // Saves temporary embedding and sends confirm back.
    // ---------------------------------------------------------
    private void handleUpdateVector() throws IOException {
        String proposalId = in.readUTF();
        long version = in.readLong();

        int n = in.readInt();
        List<String> vector = new ArrayList<>(n);
        for (int i = 0; i < n; i++) vector.add(in.readUTF());

        String newCid = in.readUTF();
        int dim = in.readInt();

        float[] emb = new float[dim];
        for (int i = 0; i < dim; i++) emb[i] = in.readFloat();

        log("UPDATE_VECTOR (TCP fallback) proposal=" + proposalId);

        pendingProposalId = proposalId;
        pendingVersion = version;
        pendingVector = vector;
        pendingHash = computeVectorHash(vector);
        pendingNewCid = newCid;

        saveTempEmbedding(newCid, emb);

        // Also send confirm via PubSub
        try {
            IPFSPubSub.publish(PUBSUB_TOPIC,
                    "{\"type\":\"CONFIRM_VECTOR\",\"proposalId\":\"" + proposalId +
                            "\",\"hash\":\"" + pendingHash +
                            "\",\"peerId\":\"" + peerIdField.getText() + "\"}");
        } catch (Exception ignored) {}

        // And send fallback confirm via TCP
        sendConfirmVectorTCP(proposalId, pendingHash);
    }


    // ---------------------------------------------------------
    // Sends CONFIRM_VECTOR via TCP fallback.
    // ---------------------------------------------------------
    private void sendConfirmVectorTCP(String proposalId, String hash) {
        new Thread(() -> {
            try {
                synchronized (out) {
                    out.writeByte(31);
                    out.writeUTF(proposalId);
                    out.writeUTF(hash);
                    out.flush();
                }
                log("CONFIRM_VECTOR sent (TCP)");
            } catch (Exception ignored) {}
        }).start();
    }


    // ---------------------------------------------------------
    // Apply COMMIT_VECTOR when received via TCP fallback.
    // Moves embedding to final storage and updates search index.
    // ---------------------------------------------------------
    private void handleCommitVector() throws IOException {
        long version = in.readLong();
        String hash = in.readUTF();

        if (pendingHash == null) return;
        if (!pendingHash.equals(hash)) return;

        confirmedVersion = version;
        confirmedVector = pendingVector;
        confirmedHash = hash;

        promoteTempToFinal(pendingNewCid);

        pendingVersion = null;
        pendingVector = null;
        pendingNewCid = null;
        pendingHash = null;
        pendingProposalId = null;

        log("Commit applied (TCP fallback). version=" + confirmedVersion);

        // --- Sprint 5 ---
        // Update index asynchronously
        VectorIndexService.getInstance().updateIndexAsync(confirmedVector, confirmedVersion);
    }


    // ---------------------------------------------------------
    // File chooser dialog for selecting local file.
    // ---------------------------------------------------------
    private void chooseFile() {
        JFileChooser fc = new JFileChooser();
        if (fc.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            fileToSend = fc.getSelectedFile();
            filePathField.setText(fileToSend.getAbsolutePath());
            log("Selected: " + fileToSend.getName());
        }
    }


    // ---------------------------------------------------------
    // Sends the selected file to the Leader via TCP.
    // ---------------------------------------------------------
    private void sendFile() {
        if (!connected) {
            log("Not connected");
            return;
        }
        if (fileToSend == null) {
            log("No file selected");
            return;
        }

        new Thread(() -> {
            try (FileInputStream fis = new FileInputStream(fileToSend)) {
                synchronized (out) {
                    out.writeByte(11);
                    out.writeUTF(fileToSend.getName());
                    out.writeLong(fileToSend.length());

                    byte[] buf = new byte[8192];
                    int r;
                    while ((r = fis.read(buf)) != -1)
                        out.write(buf, 0, r);

                    out.flush();
                }
                log("File sent.");
            } catch (Exception e) {
                log("Send error: " + e.getMessage());
            }
        }).start();
    }


    // ---------------------------------------------------------
    // Gracefully closes the TCP connection.
    // ---------------------------------------------------------
    private void disconnect() {
        connected = false;
        try { if (socket != null) socket.close(); } catch (IOException ignored) {}
        connectButton.setEnabled(true);
        disconnectButton.setEnabled(false);
        log("Disconnected.");
    }


    // ---------------------------------------------------------
    // Hash of vector for validation.
    // ---------------------------------------------------------
    private static String computeVectorHash(List<String> vector) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            String joined = String.join("|", vector);
            byte[] digest = md.digest(joined.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return "hash_err";
        }
    }


    // ---------------------------------------------------------
    // Save embedding temporarily until commit arrives.
    // ---------------------------------------------------------
    private void saveTempEmbedding(String cid, float[] embedding) {
        try {
            File dir = new File("state/temp_embeddings");
            dir.mkdirs();
            File f = new File(dir, "embedding_" + cid + ".txt");
            try (PrintWriter pw = new PrintWriter(new FileWriter(f))) {
                for (float v : embedding) pw.print(v + ",");
            }
        } catch (Exception e) {
            log("Temp save error: " + e.getMessage());
        }
    }


    // ---------------------------------------------------------
    // Move temporary embedding to the final directory.
    // ---------------------------------------------------------
    private void promoteTempToFinal(String cid) {
        try {
            File temp = new File("state/temp_embeddings/embedding_" + cid + ".txt");
            File fin = new File("state/embeddings/embedding_" + cid + ".txt");
            fin.getParentFile().mkdirs();
            if (temp.exists())
                Files.move(temp.toPath(), fin.toPath(), StandardCopyOption.REPLACE_EXISTING);

            log("Embedding promoted → " + fin.getAbsolutePath());
        } catch (Exception e) {
            log("Promote error: " + e.getMessage());
        }
    }


    // Log helper
    private void log(String msg) {
        AppLog.log(msg);
    }


    // ---------------------------------------------------------
    // Program entry point.
    // ---------------------------------------------------------
    public static void main(String[] args) {
        SwingUtilities.invokeLater(TCPClient::new);
    }
}
