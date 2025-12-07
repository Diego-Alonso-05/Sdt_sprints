import common.AppLog;
import common.MessageBus;

import javax.swing.*;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * TCPServer (Leader)
 * ------------------
 * Handles:
 *  - incoming peer connections (TCP)
 *  - receiving file uploads from peers (handled by Connection class)
 *  - managing vector proposals
 *  - publishing UPDATE_VECTOR and COMMIT_VECTOR messages over MQTT
 *  - receiving CONFIRM_VECTOR messages from peers
 *  - GUI logging and client count display
 *
 * The Leader does NOT use IPFS PubSub anymore.
 * All message distribution uses MessageBus (MQTT).
 */
public class TCPServer extends JFrame {

    private static final int PORT = 5000;

    /** MQTT topic used for all Leader↔Peers vector operations */
    private static final String PUBSUB_TOPIC = "sdt/vector";

    private static volatile boolean running = false;
    private static ServerSocket serverSocket;
    private static Thread acceptThread;

    private static final AtomicInteger CONNECTED_CLIENTS = new AtomicInteger(0);
    private static final List<Connection> CONNECTIONS = new CopyOnWriteArrayList<>();

    /** Vector global comprometido (vista actual en el Leader) */
    private static final List<String> GLOBAL_VECTOR = new CopyOnWriteArrayList<>();

    /** Propuestas pendientes de commit */
    private static final Map<String, Proposal> PENDING = new ConcurrentHashMap<>();

    private JTextArea logArea;
    private JLabel clientCountLabel;

    private JButton startButton;
    private JButton stopButton;

    private JTextField messageField;
    private JButton sendMsgButton;

    public TCPServer() {
        super("Leader Server");

        // ================= GUI SETUP =================
        AppLog.setSink(this::log);

        logArea = new JTextArea(20, 70);
        logArea.setEditable(false);
        JScrollPane scrollPane = new JScrollPane(logArea);

        clientCountLabel = new JLabel("Connected clients: 0");

        startButton = new JButton("Start Server");
        stopButton = new JButton("Stop Server");
        stopButton.setEnabled(false);

        messageField = new JTextField(20);
        sendMsgButton = new JButton("Broadcast");

        JPanel top = new JPanel(new FlowLayout(FlowLayout.LEFT));
        top.add(startButton);
        top.add(stopButton);
        top.add(clientCountLabel);

        JPanel bottom = new JPanel(new FlowLayout(FlowLayout.LEFT));
        bottom.add(new JLabel("Message:"));
        bottom.add(messageField);
        bottom.add(sendMsgButton);

        setLayout(new BorderLayout());
        add(top, BorderLayout.NORTH);
        add(scrollPane, BorderLayout.CENTER);
        add(bottom, BorderLayout.SOUTH);

        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        pack();
        setLocationRelativeTo(null);

        // Button actions
        startButton.addActionListener(e -> startServer());
        stopButton.addActionListener(e -> stopServer());
        sendMsgButton.addActionListener(e -> broadcastMessage());

        // Start MQTT listener
        startMQTTListener();
    }

    // =========================================================================
    //                                LOGGING
    // =========================================================================
    private void log(String msg) {
        SwingUtilities.invokeLater(() -> {
            logArea.append(msg + "\n");
            logArea.setCaretPosition(logArea.getDocument().getLength());
        });
        System.out.println(msg);
    }

    // =========================================================================
    //                         START / STOP LEADER SERVER
    // =========================================================================
    private void startServer() {
        if (running) return;
        running = true;

        try {
            serverSocket = new ServerSocket(PORT);
            log("Leader listening on port " + PORT);

            startButton.setEnabled(false);
            stopButton.setEnabled(true);

            acceptThread = new Thread(() -> {
                while (running) {
                    try {
                        Socket socket = serverSocket.accept();
                        Connection conn = new Connection(socket, this);
                        CONNECTIONS.add(conn);
                        CONNECTED_CLIENTS.incrementAndGet();
                        updateClientCount();
                        new Thread(conn).start();

                    } catch (IOException e) {
                        if (running) log("Accept failed: " + e.getMessage());
                    }
                }
            }, "leader-accept-thread");

            acceptThread.setDaemon(true);
            acceptThread.start();

        } catch (Exception e) {
            log("Failed to start leader: " + e.getMessage());
        }
    }

    private void stopServer() {
        running = false;

        try {
            if (serverSocket != null) serverSocket.close();
        } catch (IOException ignored) {}

        for (Connection c : CONNECTIONS) {
            c.closeQuietly();
        }
        CONNECTIONS.clear();

        if (acceptThread != null && acceptThread.isAlive())
            acceptThread.interrupt();

        startButton.setEnabled(true);
        stopButton.setEnabled(false);
        log("Leader stopped.");
    }

    private void updateClientCount() {
        SwingUtilities.invokeLater(() ->
                clientCountLabel.setText("Connected clients: " + CONNECTED_CLIENTS.get()));
    }

    // =========================================================================
    //                             MQTT LISTENER
    // =========================================================================
    private void startMQTTListener() {

        // Subscribe to all vector-related Peer→Leader messages
        MessageBus.subscribe(PUBSUB_TOPIC, json ->
                SwingUtilities.invokeLater(() -> processMQTTMessage(json)));

        log("MQTT listener active on topic: " + PUBSUB_TOPIC);
    }

    // =========================================================================
    //                         MQTT MESSAGE PROCESSING
    // =========================================================================
    private void processMQTTMessage(String json) {

        log("[MQTT] " + json);

        if (json.contains("\"type\":\"CONFIRM_VECTOR\"")) {
            String proposalId = extract(json, "proposalId");
            String peerId     = extract(json, "peerId");
            String hash       = extract(json, "hash");

            handlePeerConfirmation(proposalId, peerId, hash);
        }
    }

    // =========================================================================
    //                       SIMPLE JSON EXTRACTION (no libs)
    // =========================================================================
    private String extract(String json, String key) {
        try {
            String a = "\"" + key + "\":\"";
            int i = json.indexOf(a);
            if (i != -1) {
                int start = i + a.length();
                int end = json.indexOf('"', start);
                return json.substring(start, end);
            }

            String b = "\"" + key + "\":";
            i = json.indexOf(b);
            if (i != -1) {
                int start = i + b.length();
                int end = start;
                while (end < json.length() &&
                        (Character.isDigit(json.charAt(end)) || json.charAt(end)=='.'))
                    end++;
                return json.substring(start, end);
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    // =========================================================================
    //                              PROPOSAL LOGIC
    // =========================================================================

    /**
     * Called by Connection when a new upload proposal is created.
     */
    public static void registerProposal(String proposalId, long version,
                                        String hash, String newCid) {
        Proposal p = new Proposal(proposalId, version, hash, newCid);
        PENDING.put(proposalId, p);
        AppLog.log("[Leader] Registered proposal " + proposalId +
                " version=" + version + " hash=" + hash);
    }

    /**
     * Called by Connection when the upload finishes successfully.
     * This is the moment when the Leader computes the new vector and
     * broadcasts UPDATE_VECTOR to all peers.
     */
    public static void applyProposal(String proposalId) {
        Proposal p = PENDING.get(proposalId);
        if (p == null) {
            AppLog.log("[Leader] Cannot apply unknown proposal: " + proposalId);
            return;
        }

        // Build new vector based on current committed state
        List<String> newVector = new ArrayList<>(GLOBAL_VECTOR);
        newVector.add(p.newCid);

        p.proposedVector = newVector;

        AppLog.log("[Leader] Applying proposal " + proposalId +
                " → broadcasting UPDATE_VECTOR");

        publishVectorUpdate(p, newVector);
    }

    private void handlePeerConfirmation(String proposalId,
                                        String peerId,
                                        String hash) {

        Proposal proposal = PENDING.get(proposalId);
        if (proposal == null) {
            AppLog.log("[Leader] Unknown proposal: " + proposalId);
            return;
        }

        if (!proposal.vectorHash.equals(hash)) {
            AppLog.log("[Leader] Hash mismatch from " + peerId +
                    " on proposal " + proposalId);
            return;
        }

        proposal.confirmations.add(peerId);

        AppLog.log("[Leader] Confirmed by " + peerId + " (" +
                proposal.confirmations.size() + " confirmations)");

        // For this simplified project: commit as soon as we receive at least one confirmation
        doCommit(proposal);
    }

    // =========================================================================
    //                          VECTOR UPDATE PUBLISHING
    // =========================================================================
    public static void publishVectorUpdate(Proposal p, Collection<String> vector) {

        StringBuilder sb = new StringBuilder();
        sb.append("{\"type\":\"UPDATE_VECTOR\",")
                .append("\"proposalId\":\"").append(p.proposalId).append("\",")
                .append("\"version\":").append(p.version).append(",")
                .append("\"hash\":\"").append(p.vectorHash).append("\",")
                .append("\"newCid\":\"").append(p.newCid).append("\",");

        sb.append("\"vector\":[");
        boolean first = true;
        for (String s : vector) {
            if (!first) sb.append(",");
            first = false;
            sb.append("\"").append(escapeJson(s)).append("\"");
        }
        sb.append("]}");

        MessageBus.publish(PUBSUB_TOPIC, sb.toString());
        AppLog.log("[Leader] UPDATE_VECTOR published for proposal " + p.proposalId);
    }

    // =========================================================================
    //                                COMMIT
    // =========================================================================
    private void doCommit(Proposal p) {

        String json = "{\"type\":\"COMMIT_VECTOR\","
                + "\"proposalId\":\"" + p.proposalId + "\","
                + "\"version\":" + p.version + ","
                + "\"hash\":\"" + p.vectorHash + "\"}";

        MessageBus.publish(PUBSUB_TOPIC, json);

        // Update global vector to the proposed one (if available)
        if (p.proposedVector != null) {
            GLOBAL_VECTOR.clear();
            GLOBAL_VECTOR.addAll(p.proposedVector);
        }

        AppLog.log("[Leader] COMMIT_VECTOR broadcast. New version=" + p.version);

        PENDING.remove(p.proposalId);
    }

    // =========================================================================
    //                             JSON ESCAPING
    // =========================================================================
    private static String escapeJson(String s) {
        return s.replace("\"", "\\\"");
    }

    // =========================================================================
    //                         BROADCAST FOR DEBUG
    // =========================================================================
    private void broadcastMessage() {
        String msg = messageField.getText();
        if (msg == null || msg.isEmpty()) return;

        String json = "{\"type\":\"BROADCAST\",\"text\":\""
                + escapeJson(msg) + "\"}";

        MessageBus.publish(PUBSUB_TOPIC, json);
        log("[Leader] Broadcast: " + json);
    }

    // =========================================================================
    //                               MAIN (opc: para debug)
    // =========================================================================
    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> new TCPServer().setVisible(true));
    }

    // =========================================================================
    //                       PROPOSAL OBJECT STRUCTURE
    // =========================================================================
    static class Proposal {

        final String proposalId;
        final long   version;
        final String vectorHash;
        final String newCid;

        /** Vector propuesto (se aplica al hacer COMMIT) */
        volatile List<String> proposedVector = null;

        final Set<String> confirmations = ConcurrentHashMap.newKeySet();

        Proposal(String proposalId, long version, String hash, String newCid) {
            this.proposalId  = proposalId;
            this.version     = version;
            this.vectorHash  = hash;
            this.newCid      = newCid;
        }
    }

    // =========================================================================
    //                       CONNECTION MANAGEMENT
    // =========================================================================
    public static void decClients(Connection conn) {
        CONNECTIONS.remove(conn);
        CONNECTED_CLIENTS.decrementAndGet();
    }
}
