import common.AppLog;
import common.MessageBus;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.Socket;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.Collections;

// Sprint 7 imports
import common.AppLog;
import common.MessageBus;
import java.util.concurrent.CopyOnWriteArrayList;
// Import the client-side file manager and vector index service
// (these classes will be added for query processing)
import javax.swing.*;

/**
 * TCPClient (Peer)
 * ----------------
 * Modified to support embedding propagation: when receiving an UPDATE_VECTOR
 * message containing an embedding array, the client stores the embedding
 * temporarily using FileManager. On commit, it promotes temporary embeddings
 * to the permanent state directory before updating the FAISS index.
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

    // ----------------------------------------------------------------------
    // Sprint 7: flag to indicate if this peer is currently processing a
    // search task. Only one query can be processed at a time per peer. When
    // busy is true, TASK_ASSIGN messages are ignored. After sending a
    // TASK_RESULT, busy is reset to false.
    private volatile boolean busy = false;

    // ----------------------------------------------------------------------
    // Root detection for client
    // ----------------------------------------------------------------------
    /**
     * Cached root directory for the client. This is computed once on
     * first access and used for locating the state directory where
     * embeddings are stored. The logic attempts to locate the ClientPeer
     * module if running from the project root, otherwise falls back to
     * the current working directory.
     */
    private static File CLIENT_ROOT = null;

    /**
     * Returns the root directory for this peer. If the application is
     * launched from within the ClientPeer module, the working directory
     * itself is returned. Otherwise, if a ClientPeer subdirectory exists
     * relative to the current working directory, that directory is used.
     * Finally, as a fallback, the current working directory is returned.
     */
    public static File getClientRoot() {
        if (CLIENT_ROOT != null) return CLIENT_ROOT;
        File cwd = new File(System.getProperty("user.dir"));
        // If a ClientPeer.iml exists here, we are inside the module
        if (new File(cwd, "ClientPeer.iml").exists()) {
            CLIENT_ROOT = cwd;
            return CLIENT_ROOT;
        }
        // If a ClientPeer directory exists one level down, use it
        File cp = new File(cwd, "ClientPeer");
        if (cp.exists()) {
            CLIENT_ROOT = cp;
            return CLIENT_ROOT;
        }
        // Fallback
        CLIENT_ROOT = cwd;
        return CLIENT_ROOT;
    }

    public TCPClient() {
        super("Peer Client");

        // We always know about ourselves
        knownPeers.add(peerId);

        setupGUI();
        startMQTTListener();
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

        JPanel top = new JPanel(new java.awt.FlowLayout());
        top.add(new JLabel("File:"));
        top.add(fileField);
        top.add(browse);
        top.add(uploadButton);
        top.add(refreshButton);

        add(top, java.awt.BorderLayout.NORTH);
        add(scrollPane, java.awt.BorderLayout.CENTER);

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
    private void startMQTTListener() {

        // Existing vector handling
        MessageBus.subscribe("sdt/vector", json ->
                SwingUtilities.invokeLater(() -> processMQTT(json)));

        // ⭐ NEW: Subscribe to leader heartbeats
        MessageBus.subscribe("sdt/heartbeat", json ->
                SwingUtilities.invokeLater(() -> {
                    log("[HB-IN] " + json);

                    if (json.contains("\"type\":\"HEARTBEAT\"")) {
                        if (heartbeatMonitor != null) {
                            heartbeatMonitor.resetTimeoutFromOutside();
                        }
                    }

                    // Forward heartbeats into RAFT if needed
                    if (raftService != null && json.contains("\"type\":\"HEARTBEAT\"")) {
                        // optional: could update internal leaderId
                    }
                }));

        log("[MQTT] Subscribed to sdt/vector and sdt/heartbeat");
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

        // Sprint 7: handle task assignments from the leader.
        if (json.contains("\"type\":\"TASK_ASSIGN\"")) {
            handleTaskAssign(json);
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
            String newCid     = extract(json, "newCid");

            // Parse embedding array if present and save to temp storage
            try {
                int embedStart = json.indexOf("\"embedding\":[");
                if (embedStart != -1) {
                    int start = embedStart + "\"embedding\": [".length();
                    // account for both possible "embedding": [ or "embedding":[ formats
                    if (json.charAt(start) == '[') {
                        start++;
                    }
                    int end   = json.indexOf(']', start);
                    if (end != -1) {
                        String embStr = json.substring(start, end);
                        String[] parts = embStr.split(",");
                        float[] emb = new float[parts.length];
                        for (int i = 0; i < parts.length; i++) {
                            String p = parts[i].trim();
                            if (!p.isEmpty()) {
                                emb[i] = Float.parseFloat(p);
                            }
                        }
                        if (newCid != null && !newCid.isEmpty()) {
                            FileManager_client.saveTempEmbedding(newCid, emb);
                        }
                    }
                }
            } catch (Exception ex) {
                log("[Peer] Failed to parse/save embedding: " + ex.getMessage());
            }

            // Parse vector list separately
            List<String> vector = new ArrayList<>();
            try {
                int vs = json.indexOf("\"vector\":[");
                if (vs != -1) {
                    int start = vs + "\"vector\":[".length();
                    int end   = json.indexOf(']', start);
                    if (end != -1) {
                        String inner = json.substring(start, end);
                        String[] parts = inner.split(",");
                        for (String p : parts) {
                            String s = p.trim();
                            if (s.startsWith("\"") && s.endsWith("\"")) {
                                s = s.substring(1, s.length() - 1);
                            }
                            if (!s.isEmpty()) {
                                vector.add(s);
                            }
                        }
                    }
                }
            } catch (Exception ex) {
                log("[Peer] Failed to parse vector: " + ex.getMessage());
            }

            synchronized (currentVector) {
                currentVector.clear();
                currentVector.addAll(vector);
            }

            log("[Peer] UPDATE_VECTOR applied (size=" + currentVector.size() + ")");

            String confirm =
                    "{\"type\":\"CONFIRM_VECTOR\"," +
                            "\"proposalId\":\"" + proposalId + "\"," +
                            "\"peerId\":\"" + peerId + "\"," +
                            "\"hash\":\"" + hash + "\"}";

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

            // Promote temporary embeddings to final storage before updating index
            List<String> snapshot;
            synchronized (currentVector) {
                snapshot = new ArrayList<>(currentVector);
            }
            for (String cid : snapshot) {
                try {
                    FileManager_client.promoteTempEmbedding(cid);
                } catch (IOException ex) {
                    log("[Peer] Failed to promote temp embedding for CID " + cid + ": " + ex.getMessage());
                }
            }

            // --------- NEW: update local embedding index ----------
            boolean accepted = VectorIndexService.getInstance()
                    .updateIndexAsync(snapshot, version);

            log("[Peer] VectorIndexService updateIndexAsync accepted=" + accepted);

        } catch (Exception e) {
            log("[Peer] COMMIT_VECTOR error: " + e.getMessage());
        }
    }

    // --------------------------------------------------------------------
    // Sprint 7: TASK_ASSIGN handler
    // --------------------------------------------------------------------
    /**
     * Processes a TASK_ASSIGN message from the leader. If this peer is not
     * currently busy processing another query, it will perform a nearest
     * neighbour search over its local vector index using the provided query
     * embedding, produce a list of top CIDs, and publish a TASK_RESULT
     * message. Only one peer should process a given query, but peers make
     * this decision independently by checking their busy flag.
     *
     * Message format:
     * {
     *   "type":"TASK_ASSIGN",
     *   "queryId":"<id>",
     *   "leaderId":"<leader-id>",
     *   "embedding":[ ... ]
     * }
     */
    private void handleTaskAssign(String json) {
        try {
            if (busy) {
                // Another query is currently being processed; ignore.
                return;
            }
            String queryId = extract(json, "queryId");
            if (queryId == null || queryId.isEmpty()) return;
            // Parse embedding array
            float[] emb = null;
            int ei = json.indexOf("\"embedding\":[");
            if (ei != -1) {
                int start = ei + "\"embedding\": [".length();
                // handle both spaced and non-spaced
                if (json.charAt(start) == '[') start++;
                int end = json.indexOf(']', start);
                if (end != -1) {
                    String body = json.substring(start, end);
                    String[] nums = body.split(",");
                    emb = new float[nums.length];
                    for (int i = 0; i < nums.length; i++) {
                        String p = nums[i].trim();
                        if (!p.isEmpty()) {
                            emb[i] = Float.parseFloat(p);
                        }
                    }
                }
            }
            if (emb == null) {
                // no embedding provided
                return;
            }
            // Mark busy
            busy = true;
            // Perform search over local index
            List<String> topCids = VectorIndexService.getInstance().search(emb, 3);
            // Build result message
            StringBuilder sb = new StringBuilder();
            sb.append("{\"type\":\"TASK_RESULT\",");
            sb.append("\"queryId\":\"").append(queryId).append("\",");
            sb.append("\"peerId\":\"").append(peerId).append("\",");
            sb.append("\"results\":[");
            for (int i = 0; i < topCids.size(); i++) {
                if (i > 0) sb.append(",");
                sb.append("\"").append(topCids.get(i)).append("\"");
            }
            sb.append("]}");
            MessageBus.publish(PUBSUB_TOPIC, sb.toString());
            log("[Peer] TASK_RESULT sent for query " + queryId + " (" + topCids.size() + ")");
        } catch (Exception e) {
            log("[Peer] TASK_ASSIGN error: " + e.getMessage());
        } finally {
            busy = false;
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