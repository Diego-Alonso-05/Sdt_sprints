import common.AppLog;

import javax.swing.*;
import java.awt.*;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

public class TCPServer extends JFrame {

    private static JTextArea logArea;
    private static JLabel clientCountLabel, ipfsStatusLabel;
    private JButton startButton, stopButton, sendMsgButton;
    private JTextField messageField;

    private ServerSocket serverSocket;
    private Thread acceptThread;
    private volatile boolean running = false;

    // List of active TCP connections to peers.
    // CopyOnWriteArrayList is used to allow concurrent iteration + modification safely.
    static final List<Connection> CONNECTIONS = new CopyOnWriteArrayList<>();
    static final AtomicInteger CLIENT_COUNT = new AtomicInteger(0);

    // Encapsulates Leader versioning, vector state and commit logic.
    static final LeaderState STATE = new LeaderState();

    // PubSub topic used by both Leader and Peers.
    private static final String PUBSUB_TOPIC = "sdt-updates";
    private Thread pubsubThread;

    // --- Sprint 5 ---
    // Heartbeat generator for advertising Leader liveness to peers.
    private LeaderHeartbeatService heartbeatService;

    private void log(String msg) {
        AppLog.log(msg);
    }

    // ---------------------------------------------------------------------
    // PendingProposal
    // Represents a proposal waiting for confirmations from peers.
    // Each peer must confirm with matching vectorHash.
    // ---------------------------------------------------------------------

    private static class PendingProposal {
        final String proposalId;      // Unique ID for this proposal
        final long version;           // Proposed version number
        final String vectorHash;      // Hash of the proposed vector
        final String newCid;          // CID of uploaded file
        final List<String> fullVector;
        final int quorumNeeded;       // Number of confirmations required

        // Peers that have confirmed this proposal
        final Set<String> confirmedPeers =
                Collections.newSetFromMap(new ConcurrentHashMap<>());

        PendingProposal(long version, String hash, String newCid,
                        List<String> vector, int quorum) {

            this.proposalId = UUID.randomUUID().toString();
            this.version = version;
            this.vectorHash = hash;
            this.newCid = newCid;
            this.fullVector = new ArrayList<>(vector);
            this.quorumNeeded = quorum;
        }
    }

    // Map: proposalId → pending proposal object.
    private static final ConcurrentHashMap<String, PendingProposal> PENDING_PROPOSALS =
            new ConcurrentHashMap<>();

    // ---------------------------------------------------------------------
    // GUI + Initialization
    // ---------------------------------------------------------------------

    public TCPServer() {

        super("Leader - Server");

        // Directs all AppLog output into the GUI log panel.
        AppLog.setSink(msg -> SwingUtilities.invokeLater(() -> {
            logArea.append(msg + "\n");
            logArea.setCaretPosition(logArea.getDocument().getLength());
        }));

        try { IPFSInit.startServerDaemon(); } catch (Exception ignored) {}

        logArea = new JTextArea(18, 70);
        logArea.setEditable(false);
        JScrollPane scrollPane = new JScrollPane(logArea);

        clientCountLabel = new JLabel("Connected clients: 0");
        ipfsStatusLabel = new JLabel("IPFS: checking...");

        startButton = new JButton("Start Server");
        stopButton = new JButton("Stop Server");
        stopButton.setEnabled(false);

        messageField = new JTextField(40);
        sendMsgButton = new JButton("Send to all");

        startButton.addActionListener(e -> startServer());
        stopButton.addActionListener(e -> stopServer());
        sendMsgButton.addActionListener(e -> {
            String msg = messageField.getText().trim();
            if (!msg.isEmpty()) {
                broadcastToAll(msg);
                messageField.setText("");
            }
        });

        JPanel top = new JPanel(new FlowLayout(FlowLayout.LEFT));
        top.add(startButton);
        top.add(stopButton);
        top.add(Box.createHorizontalStrut(10));
        top.add(clientCountLabel);
        top.add(Box.createHorizontalStrut(20));
        top.add(ipfsStatusLabel);

        JPanel bottom = new JPanel(new FlowLayout(FlowLayout.LEFT));
        bottom.add(new JLabel("Message:"));
        bottom.add(messageField);
        bottom.add(sendMsgButton);

        setLayout(new BorderLayout());
        add(top, BorderLayout.NORTH);
        add(scrollPane, BorderLayout.CENTER);
        add(bottom, BorderLayout.SOUTH);

        setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
        pack();
        setLocationRelativeTo(null);
        setVisible(true);

        // --- Sprint 5 ---
        // Start PubSub listener on startup.
        startPubSubListener();

        // --- Sprint 5 ---
        // Start Leader heartbeat broadcasting.
        heartbeatService = new LeaderHeartbeatService("Leader-1", PUBSUB_TOPIC);
        heartbeatService.start();
    }

    // ---------------------------------------------------------------------
    // PubSub Listener (Leader)
    // Listens for peer confirmations and logs activity.
    // ---------------------------------------------------------------------

    private void startPubSubListener() {

        if (pubsubThread != null && pubsubThread.isAlive()) {
            log("PubSub listener already running.");
            return;
        }

        pubsubThread = new Thread(() -> {
            try {
                // Subscribe to topic and dispatch messages to GUI thread.
                IPFSPubSub.subscribe(PUBSUB_TOPIC, json ->
                        SwingUtilities.invokeLater(() -> processPubSubMessage(json))
                );
            } catch (Exception e) {
                log("PubSub listener error: " + e.getMessage());
            }
        }, "server-pubsub-listener");

        pubsubThread.setDaemon(true);
        pubsubThread.start();

        log("PubSub listener started on topic: " + PUBSUB_TOPIC);
    }

    private void processPubSubMessage(String json) {

        if (json == null) return;

        // Some IPFS implementations wrap payload in "data": "BASE64..."
        String decoded = tryExtractBase64Data(json);
        if (decoded != null) {
            json = decoded;
        }

        // --- Sprint 5 ---
        // Process peer confirmations.
        if (json.contains("\"type\":\"CONFIRM_VECTOR\"")) {

            String id = extract(json, "proposalId");
            String hash = extract(json, "hash");
            String peer = extract(json, "peerId");

            log("CONFIRM_VECTOR via PubSub from " + peer +
                    " proposal=" + id);

            handlePeerConfirmation(id, peer, hash);
            return;
        }

        if (json.contains("\"type\":\"UPDATE_VECTOR\"")) {
            String id = extract(json, "proposalId");
            log("[PubSub] UPDATE_VECTOR received proposal=" + id);
            return;
        }

        if (json.contains("\"type\":\"COMMIT_VECTOR\"")) {
            String id = extract(json, "proposalId");
            log("[PubSub] COMMIT_VECTOR received proposal=" + id);
        }
    }

    // Extract Base64 "data" field from IPFS envelope.
    private String tryExtractBase64Data(String env) {
        try {
            String key = "\"data\":\"";
            int i = env.indexOf(key);
            if (i == -1) return null;

            int start = i + key.length();
            int end = env.indexOf('"', start);
            if (end == -1) return null;

            String base64 = env.substring(start, end);
            byte[] decoded = Base64.getDecoder().decode(base64);
            return new String(decoded, StandardCharsets.UTF_8);

        } catch (Exception e) {
            return null;
        }
    }

    // Simple string extractor for JSON-like structures.
    private String extract(String json, String key) {
        String k = "\"" + key + "\":\"";
        int i = json.indexOf(k);
        if (i != -1) {
            int j = json.indexOf("\"", i + k.length());
            return json.substring(i + k.length(), j);
        }
        return null;
    }

    // ---------------------------------------------------------------------
    // TCP Server
    // Accepts incoming peer connections and spawns Connection threads.
    // ---------------------------------------------------------------------

    private void startServer() {
        if (running) return;
        try {
            serverSocket = new ServerSocket(5000);
            running = true;
            log("Leader listening on port 5000...");

            startButton.setEnabled(false);
            stopButton.setEnabled(true);

            acceptThread = new Thread(() -> {
                while (running) {
                    try {
                        Socket s = serverSocket.accept();
                        log("Client connected: " + s.getInetAddress().getHostAddress());

                        Connection c = new Connection(s);
                        CONNECTIONS.add(c);
                        c.start();

                    } catch (IOException e) {
                        if (running) log("accept() error: " + e.getMessage());
                    }
                }
            });

            acceptThread.start();

        } catch (IOException e) {
            log("Unable to open port: " + e.getMessage());
        }
    }

    private void stopServer() {
        running = false;

        try { serverSocket.close(); } catch (Exception ignored) {}

        for (Connection c : CONNECTIONS)
            c.closeQuietly();

        CONNECTIONS.clear();
        clientCountLabel.setText("Connected clients: 0");

        startButton.setEnabled(true);
        stopButton.setEnabled(false);

        log("Server stopped.");
    }

    // ---------------------------------------------------------------------
    // Broadcast: TCP-only message push to all connected peers.
    // ---------------------------------------------------------------------

    private void broadcastToAll(String msg) {
        int ok = 0;
        for (Connection c : CONNECTIONS) {
            try {
                c.sendBroadcast(msg);
                ok++;
            } catch (Exception ignored) {}
        }
        log("Broadcast sent to " + ok + " clients.");
    }

    // ---------------------------------------------------------------------
    // Client count handling (GUI update).
    // ---------------------------------------------------------------------

    public static void incClients() {
        int count = CLIENT_COUNT.incrementAndGet();
        SwingUtilities.invokeLater(() ->
                clientCountLabel.setText("Connected clients: " + count));
    }

    public static void decClients(Connection c) {
        CONNECTIONS.remove(c);
        int count = CLIENT_COUNT.decrementAndGet();
        SwingUtilities.invokeLater(() ->
                clientCountLabel.setText("Connected clients: " + count));
    }

    // ---------------------------------------------------------------------
    // Proposal creation (Leader)
    // Triggered when a peer uploads a file.
    // Leader constructs a new vector, new version, new hash.
    // ---------------------------------------------------------------------

    public static void beginProposalFromUpload(String newCid, float[] embedding) {

        // Build proposal using LeaderState
        LeaderState.Proposal p = STATE.beginProposal(newCid);

        // Save embedding temporarily until commit is confirmed.
        try {
            FileManager.saveTempEmbedding(newCid, embedding);
        } catch (Exception e) {
            AppLog.log("Failed to save embedding: " + e.getMessage());
        }

        int peers = CONNECTIONS.size();
        int quorum = (peers / 2) + 1;   // Simple majority rule

        PendingProposal proposal = new PendingProposal(
                p.version, p.vectorHash, newCid, p.fullVector, quorum
        );

        PENDING_PROPOSALS.put(proposal.proposalId, proposal);

        AppLog.log("New proposal " + proposal.proposalId);

        // Send UPDATE_VECTOR via TCP fallback to all peers.
        for (Connection c : CONNECTIONS) {
            try {
                c.sendUpdateVectorWithId(
                        proposal.proposalId,
                        p.version,
                        p.fullVector,
                        proposal.newCid,
                        embedding
                );
            } catch (Exception ex) {
                AppLog.log("TCP send fail to " + c.getPeerAddress());
            }
        }

        // --- Sprint 5 ---
        // Publish UPDATE_VECTOR using PubSub (primary channel).
        try {
            String m = "{\"type\":\"UPDATE_VECTOR\",\"proposalId\":\"" +
                    proposal.proposalId +
                    "\",\"version\":" + proposal.version +
                    ",\"hash\":\"" + proposal.vectorHash +
                    "\",\"newCid\":\"" + proposal.newCid + "\"}";

            IPFSPubSub.publish(PUBSUB_TOPIC, m);
        } catch (Exception e) {
            AppLog.log("PubSub publish failed: " + e.getMessage());
        }
    }

    // ---------------------------------------------------------------------
    // Handle confirmations from peers (PubSub or TCP fallback)
    // ---------------------------------------------------------------------

    static void handlePeerConfirmation(String proposalId, String peerId, String hash) {

        PendingProposal proposal = PENDING_PROPOSALS.get(proposalId);
        if (proposal == null) {
            AppLog.log("IGNORE confirm: unknown proposal");
            return;
        }

        if (!proposal.vectorHash.equals(hash)) {
            AppLog.log("IGNORE confirm: hash mismatch");
            return;
        }

        // Only count the first confirmation from each peer
        if (!proposal.confirmedPeers.add(peerId)) {
            AppLog.log("Duplicate confirmation ignored");
            return;
        }

        int count = proposal.confirmedPeers.size();
        AppLog.log("CONFIRM (" + count + "/" + proposal.quorumNeeded + ") from " + peerId);

        // Once quorum achieved → commit
        if (count >= proposal.quorumNeeded) {
            commitProposal(proposal);
        }
    }

    // ---------------------------------------------------------------------
    // Commit: apply official update to vector + publish commit
    // ---------------------------------------------------------------------

    private static void commitProposal(PendingProposal proposal) {

        // 1. Update LeaderState vector & version
        STATE.applyCommit(proposal.fullVector, proposal.version, proposal.vectorHash);

        // 2. Promote embedding from temp → final folder
        try {
            FileManager.promoteTempEmbedding(proposal.newCid);
        } catch (Exception ignored) {}

        // 3. TCP fallback commit to peers
        for (Connection c : CONNECTIONS) {
            try {
                c.sendCommitVector(proposal.version, proposal.vectorHash);
            } catch (Exception ignored) {}
        }

        // --- Sprint 5 ---
        // 4. PubSub commit broadcast
        try {
            String json = "{\"type\":\"COMMIT_VECTOR\",\"proposalId\":\"" +
                    proposal.proposalId +
                    "\",\"version\":" + proposal.version +
                    ",\"hash\":\"" + proposal.vectorHash + "\"}";

            IPFSPubSub.publish(PUBSUB_TOPIC, json);

        } catch (Exception e) {
            AppLog.log("PubSub commit fail: " + e.getMessage());
        }

        // 5. Remove proposal
        PENDING_PROPOSALS.remove(proposal.proposalId);
    }

    // ---------------------------------------------------------------------
    // IPFS status helper (GUI)
    // ---------------------------------------------------------------------

    public static void setIpfsStatus(String status) {
        SwingUtilities.invokeLater(() -> {
            if (ipfsStatusLabel != null)
                ipfsStatusLabel.setText("IPFS: " + status);
        });
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(TCPServer::new);
    }
}
