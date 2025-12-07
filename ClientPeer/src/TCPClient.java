import common.AppLog;
import common.MessageBus;

import javax.swing.*;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * TCPClient (Peer)
 * ----------------
 * Responsibilities:
 *   - Upload files to the Leader using TCP
 *   - Maintain a local in-memory vector
 *   - Receive UPDATE_VECTOR messages via MQTT
 *   - Send CONFIRM_VECTOR messages via MQTT
 *   - Apply COMMIT_VECTOR updates
 *   - Participate in Raft leader election (via MQTT)
 *   - Discover other peers via MQTT
 *   - GUI for file selection and logs
 */
public class TCPClient extends JFrame {

    /** MQTT topic for vector / Raft coordination */
    private static final String PUBSUB_TOPIC      = "sdt/vector";
    /** MQTT topic for peer discovery (who is online) */
    private static final String DISCOVERY_TOPIC   = "sdt/peers";
    /** TCP Leader upload port */
    private static final int    LEADER_PORT       = 5000;

    private JTextArea logArea;
    private JButton   uploadButton;
    private JButton   refreshButton;
    private JTextField fileField;

    /** Logical version of the current committed vector */
    private final AtomicLong localVersion = new AtomicLong(0);

    /** Latest vector received and committed */
    private final List<String> currentVector = new ArrayList<>();

    /** Unique ID for this peer */
    private final String peerId = UUID.randomUUID().toString();

    /** Known peers (including self). Used for Raft majority calculation. */
    private final Set<String> knownPeers = ConcurrentHashMap.newKeySet();

    /** RAFT + Heartbeat injected by ClientMain */
    private RaftElectionService   raftService;
    private PeerHeartbeatMonitor  heartbeatMonitor;

    public TCPClient() {
        super("Peer Client");

        // We always know about ourselves
        knownPeers.add(peerId);

        setupGUI();
        startMQTTListeners();
        startDiscovery();

        AppLog.setSink(this::log);
        log("[Peer] Started with peerId=" + peerId);
    }

    // =========================================================================
    // GUI SETUP
    // =========================================================================
    private void setupGUI() {
        logArea = new JTextArea(18, 60);
        logArea.setEditable(false);
        JScrollPane scrollPane = new JScrollPane(logArea);

        uploadButton  = new JButton("Upload File");
        refreshButton = new JButton("Refresh Vector");

        fileField = new JTextField(20);
        JButton browse = new JButton("...");

        JPanel top = new JPanel(new FlowLayout());
        top.add(new JLabel("File:"));
        top.add(fileField);
        top.add(browse);
        top.add(uploadButton);
        top.add(refreshButton);

        add(top, BorderLayout.NORTH);
        add(scrollPane, BorderLayout.CENTER);

        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        pack();
        setLocationRelativeTo(null);

        browse.addActionListener(e -> selectFile());
        uploadButton.addActionListener(e -> uploadFile());
        refreshButton.addActionListener(e -> refreshVector());
    }

    // Log helper
    private void log(String msg) {
        SwingUtilities.invokeLater(() -> {
            logArea.append(msg + "\n");
            logArea.setCaretPosition(logArea.getDocument().getLength());
        });
    }

    // =========================================================================
    // FILE SELECTION
    // =========================================================================
    private void selectFile() {
        JFileChooser chooser = new JFileChooser();
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            fileField.setText(chooser.getSelectedFile().getAbsolutePath());
        }
    }

    // =========================================================================
    // FILE UPLOAD (TCP)
    // =========================================================================
    private void uploadFile() {
        String path = fileField.getText();
        if (path == null || path.isEmpty()) {
            log("Select a file first.");
            return;
        }

        File file = new File(path);
        if (!file.exists()) {
            log("File does not exist.");
            return;
        }

        try (Socket socket = new Socket("127.0.0.1", LEADER_PORT);
             OutputStream out = socket.getOutputStream();
             BufferedOutputStream bos = new BufferedOutputStream(out);
             FileInputStream fis = new FileInputStream(file)) {

            String proposalId = UUID.randomUUID().toString();
            long newVersion   = localVersion.get() + 1;
            String hash       = computeHashOfFile(file);
            String cid        = "cid-" + proposalId;

            log("[Peer] Uploading proposal: " + proposalId);
            log(" version=" + newVersion);

            bos.write((proposalId + "\n").getBytes());
            bos.write((newVersion + "\n").getBytes());
            bos.write((hash + "\n").getBytes());
            bos.write((cid + "\n").getBytes());
            bos.flush();

            byte[] buffer = new byte[4096];
            int read;
            while ((read = fis.read(buffer)) != -1) {
                bos.write(buffer, 0, read);
            }
            bos.flush();

            log("[Peer] Upload complete.");

        } catch (Exception e) {
            log("[Peer] Upload failed: " + e.getMessage());
        }
    }

    private String computeHashOfFile(File file) {
        try (FileInputStream fis = new FileInputStream(file)) {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[4096];
            int r;
            while ((r = fis.read(buf)) != -1) {
                md.update(buf, 0, r);
            }
            StringBuilder sb = new StringBuilder();
            for (byte b : md.digest())
                sb.append(String.format("%02x", b & 0xff));
            return sb.toString();
        } catch (Exception e) {
            log("[Peer] Hash error: " + e.getMessage());
            return "0";
        }
    }

    // =========================================================================
    // MQTT LISTENERS
    // =========================================================================
    /**
     * Subscribes both to:
     *  - PUBSUB_TOPIC: vector / Raft messages
     *  - DISCOVERY_TOPIC: peer discovery (online/offline)
     */
    private void startMQTTListeners() {
        // Vector + Raft messages
        MessageBus.subscribe(PUBSUB_TOPIC,
                json -> SwingUtilities.invokeLater(() -> processMQTT(json)));

        // Peer discovery messages
        MessageBus.subscribe(DISCOVERY_TOPIC,
                json -> SwingUtilities.invokeLater(() -> processDiscovery(json)));

        log("[MQTT] Subscribed to " + PUBSUB_TOPIC + " and " + DISCOVERY_TOPIC);
    }

    // =========================================================================
    // PEER DISCOVERY
    // =========================================================================
    /**
     * Announces this peer as ONLINE and registers a shutdown hook
     * to broadcast OFFLINE on JVM exit.
     */
    private void startDiscovery() {

        // Announce ourselves as online
        String onlineJson = "{\"type\":\"PEER_ONLINE\",\"peerId\":\"" + peerId + "\"}";
        MessageBus.publish(DISCOVERY_TOPIC, onlineJson);
        log("[DISCOVERY] Announced PEER_ONLINE for " + peerId);

        // On JVM shutdown, try to announce OFFLINE
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            String offlineJson =
                    "{\"type\":\"PEER_OFFLINE\",\"peerId\":\"" + peerId + "\"}";
            MessageBus.publish(DISCOVERY_TOPIC, offlineJson);
        }));
    }

    private void processDiscovery(String json) {

        if (json.contains("\"type\":\"PEER_ONLINE\"")) {
            String id = extract(json, "peerId");
            if (id != null && !id.isBlank()) {
                knownPeers.add(id);
                log("[DISCOVERY] Peer online: " + id + " (total=" + knownPeers.size() + ")");
            }
            return;
        }

        if (json.contains("\"type\":\"PEER_OFFLINE\"")) {
            String id = extract(json, "peerId");
            if (id != null && !id.isBlank()) {
                knownPeers.remove(id);
                log("[DISCOVERY] Peer offline: " + id + " (total=" + knownPeers.size() + ")");
            }
        }
    }

    // =========================================================================
    // PROCESSING MQTT MESSAGES (VECTOR + RAFT)
    // =========================================================================
    private void processMQTT(String json) {

        log("[MQTT-IN] " + json);

        // Vector operations
        if (json.contains("\"type\":\"UPDATE_VECTOR\"")) {
            handleUpdate(json);
            return;
        }

        if (json.contains("\"type\":\"COMMIT_VECTOR\"")) {
            handleCommit(json);
            return;
        }

        // RAFT messages
        if (raftService != null) {

            if (json.contains("\"type\":\"RAFT_REQUEST_VOTE\"")) {
                raftService.handleRequestVote(json);
                return;
            }

            if (json.contains("\"type\":\"RAFT_VOTE_RESPONSE\"")) {
                raftService.handleVoteResponse(json);
                return;
            }

            if (json.contains("\"type\":\"RAFT_NEW_LEADER\"")) {
                raftService.handleNewLeader(json);
                return;
            }

            // Heartbeats would usually be processed by a PeerHeartbeatMonitor,
            // but if you want, you can also route them here.
            if (json.contains("\"type\":\"HEARTBEAT\"") && heartbeatMonitor != null) {
                heartbeatMonitor.handleHeartbeat(json);
            }
        }
    }

    // =========================================================================
    // UPDATE_VECTOR HANDLER
    // =========================================================================
    private void handleUpdate(String json) {
        try {
            String proposalId = extract(json, "proposalId");
            long version      = Long.parseLong(extract(json, "version"));
            String hash       = extract(json, "hash");

            List<String> vector = extractList(json);

            synchronized (currentVector) {
                currentVector.clear();
                currentVector.addAll(vector);
            }

            log("[Peer] UPDATE_VECTOR applied (size=" + currentVector.size() + ")");

            String confirm =
                    "{\"type\":\"CONFIRM_VECTOR\","
                            + "\"proposalId\":\"" + proposalId + "\","
                            + "\"peerId\":\"" + peerId + "\","
                            + "\"hash\":\"" + hash + "\"}";

            MessageBus.publish(PUBSUB_TOPIC, confirm);
            log("[Peer] Sent CONFIRM_VECTOR");

        } catch (Exception e) {
            log("[Peer] UPDATE_VECTOR error: " + e.getMessage());
        }
    }

    // =========================================================================
    // COMMIT_VECTOR HANDLER
    // =========================================================================
    private void handleCommit(String json) {
        try {
            long version = Long.parseLong(extract(json, "version"));
            String proposalId = extract(json, "proposalId");

            localVersion.set(version);

            log("[Peer] COMMIT_VECTOR applied. New version=" + version);

            // --------- NEW: update local embedding index ----------
            List<String> snapshot;
            synchronized (currentVector) {
                snapshot = new ArrayList<>(currentVector);
            }

            boolean accepted = VectorIndexService.getInstance()
                    .updateIndexAsync(snapshot, version);

            log("[Peer] VectorIndexService updateIndexAsync accepted=" + accepted);

        } catch (Exception e) {
            log("[Peer] COMMIT_VECTOR error: " + e.getMessage());
        }
    }


    // =========================================================================
    // JSON HELPERS
    // =========================================================================
    private String extract(String json, String key) {
        try {
            String pattern = "\"" + key + "\":\"";
            int i = json.indexOf(pattern);
            if (i != -1) {
                int start = i + pattern.length();
                int end   = json.indexOf('"', start);
                return json.substring(start, end);
            }
            pattern = "\"" + key + "\":";
            i = json.indexOf(pattern);
            if (i != -1) {
                int start = i + pattern.length();
                int end   = start;
                while (end < json.length() &&
                        (Character.isDigit(json.charAt(end)) ||
                                json.charAt(end) == '.')) end++;
                return json.substring(start, end);
            }
        } catch (Exception ignored) {}
        return null;
    }

    private List<String> extractList(String json) {
        List<String> out = new ArrayList<>();
        try {
            int s = json.indexOf('[');
            int e = json.indexOf(']');
            if (s == -1 || e == -1) return out;

            String inner = json.substring(s + 1, e);
            String[] parts = inner.split(",");

            for (String p : parts) {
                p = p.trim();
                if (p.startsWith("\"")) p = p.substring(1);
                if (p.endsWith("\""))   p = p.substring(0, p.length() - 1);
                if (!p.isBlank()) out.add(p);
            }

        } catch (Exception ignored) {}
        return out;
    }

    // =========================================================================
    // REFRESH VECTOR
    // =========================================================================
    private void refreshVector() {
        log("Vector version " + localVersion.get() + ":");
        synchronized (currentVector) {
            for (String s : currentVector)
                log("  - " + s);
        }
    }

    // =========================================================================
    // ACCESSORS FOR ClientMain / RAFT
    // =========================================================================
    public String getPeerId() { return peerId; }

    public long getLocalVersion() { return localVersion.get(); }

    /** Used by RaftElectionService to compute majority. */
    public int getPeerCount() { return knownPeers.size(); }

    public void setRaftService(RaftElectionService rs) { this.raftService = rs; }

    public void setHeartbeatMonitor(PeerHeartbeatMonitor hb) { this.heartbeatMonitor = hb; }
}
