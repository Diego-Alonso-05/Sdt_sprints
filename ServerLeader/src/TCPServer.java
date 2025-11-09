import javax.swing.*;
import java.awt.*;
import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.*;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * TCPServer
 * ----------
 * Leader (Server) node.
 * - Starts/stops the TCP server
 * - Accepts client connections
 * - Sends broadcasts
 * - Coordinates vector proposal -> confirmations -> commit
 * - Persists embeddings (temp until commit)
 *
 * NOTE: IPFS startup/stop handled elsewhere (ServerMain/IPFSInit). We do not change that here.
 */
public class TCPServer extends JFrame {

    private static JTextArea logArea;
    private static JLabel clientCountLabel, ipfsStatusLabel;
    private JButton startButton, stopButton, sendMsgButton;
    private JTextField messageField;

    private ServerSocket serverSocket;
    private Thread acceptThread;
    private volatile boolean running = false;

    private static Process ipfsProcess; // unchanged; still used by shutdown hook

    static final List<Connection> CONNECTIONS = new CopyOnWriteArrayList<>();
    static final AtomicInteger CLIENT_COUNT = new AtomicInteger(0);
    static final LeaderState STATE = new LeaderState();

    /** In-flight proposal metadata tracked by the leader to decide majority and commit. */
    private static class PendingProposal {
        final long version;
        final String vectorHash;
        final String newCid;
        final int quorumNeeded;
        final Set<String> confirmedPeers = new HashSet<>();

        PendingProposal(long version, String vectorHash, String newCid, int quorumNeeded) {
            this.version = version;
            this.vectorHash = vectorHash;
            this.newCid = newCid;
            this.quorumNeeded = quorumNeeded;
        }
    }

    /** Only one proposal at a time (simple linearizable log). */
    private static volatile PendingProposal CURRENT_PROPOSAL = null;

    public TCPServer() {
        super("Leader - Server");

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

        JPanel topPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        topPanel.add(startButton);
        topPanel.add(stopButton);
        topPanel.add(Box.createHorizontalStrut(10));
        topPanel.add(clientCountLabel);
        topPanel.add(Box.createHorizontalStrut(20));
        topPanel.add(ipfsStatusLabel);

        JPanel bottomPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        bottomPanel.add(new JLabel("Message:"));
        bottomPanel.add(messageField);
        bottomPanel.add(sendMsgButton);

        setLayout(new BorderLayout());
        add(topPanel, BorderLayout.NORTH);
        add(scrollPane, BorderLayout.CENTER);
        add(bottomPanel, BorderLayout.SOUTH);

        setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
        pack();
        setLocationRelativeTo(null);

        addWindowListener(new java.awt.event.WindowAdapter() {
            @Override
            public void windowClosing(java.awt.event.WindowEvent e) {
                shutdownIpfs(); // unchanged
                System.exit(0);
            }
        });

        setVisible(true);
    }

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
                        Socket socket = serverSocket.accept();
                        log("Client connected: " + socket.getInetAddress().getHostAddress());
                        Connection connection = new Connection(socket);
                        CONNECTIONS.add(connection);
                        connection.start();
                    } catch (IOException e) {
                        if (running) log("accept() error: " + e.getMessage());
                    }
                }
                log("Accept thread terminated.");
            }, "accept-thread");
            acceptThread.start();

        } catch (IOException e) {
            log("Unable to open port: " + e.getMessage());
        }
    }

    private void stopServer() {
        running = false;
        try { if (serverSocket != null) serverSocket.close(); } catch (IOException ignored) {}
        for (Connection c : CONNECTIONS) try { c.closeQuietly(); } catch (Exception ignored) {}
        CONNECTIONS.clear();
        clientCountLabel.setText("Connected clients: 0");
        startButton.setEnabled(true);
        stopButton.setEnabled(false);
        log("Server stopped.");
    }

    private void broadcastToAll(String msg) {
        int ok = 0;
        for (Connection c : CONNECTIONS) {
            try { c.sendBroadcast(msg); ok++; } catch (Exception ignored) {}
        }
        log("Broadcast sent to " + ok + " clients.");
    }

    // ---------------------------
    // LEADER COORDINATION LOGIC
    // ---------------------------

    /**
     * Called by a Connection after a file upload completes.
     * This begins the "proposal" step: compute pending vector, store temp embedding,
     * broadcast UPDATE_VECTOR and wait for CONFIRM_VECTOR messages.
     */
    static void beginProposalFromUpload(String newCid, float[] embedding) {
        synchronized (TCPServer.class) {
            if (CURRENT_PROPOSAL != null) {
                log("A proposal is already in flight; rejecting new proposal for CID " + newCid);
                return;
            }

            LeaderState.Proposal p = STATE.beginProposal(newCid);
            // Persist embedding temporarily (until commit)
            try {
                FileManager.saveTempEmbedding(newCid, embedding);
                log("Temp embedding saved for CID " + newCid);
            } catch (IOException ioe) {
                log("Failed to save temp embedding for " + newCid + ": " + ioe.getMessage());
            }

            int peers = CONNECTIONS.size();
            int quorum = (peers / 2) + 1; // majority of connected peers
            CURRENT_PROPOSAL = new PendingProposal(p.version, p.vectorHash, newCid, quorum);

            // Broadcast UPDATE_VECTOR to all peers
            int ok = 0;
            for (Connection c : CONNECTIONS) {
                try {
                    c.sendUpdateVector(p.version, p.fullVector, p.newCid, embedding); // opcode 21
                    ok++;
                } catch (IOException ignored) {}
            }

            log("UPDATE_VECTOR v" + p.version + " (" + p.vectorHash + ") sent to " + ok + " peers. " +
                    "Waiting for confirmations: need " + quorum + ".");
        }
    }

    /**
     * Called by a Connection when a peer sends CONFIRM_VECTOR.
     * If the confirmation matches the current proposal hash, count it and commit on quorum.
     */
    static void handlePeerConfirmation(String peerId, String vectorHash) {
        synchronized (TCPServer.class) {
            if (CURRENT_PROPOSAL == null) {
                log("CONFIRM_VECTOR from " + peerId + " ignored (no active proposal).");
                return;
            }
            if (!CURRENT_PROPOSAL.vectorHash.equals(vectorHash)) {
                log("CONFIRM_VECTOR hash mismatch from " + peerId + " (got " + vectorHash +
                        ", expecting " + CURRENT_PROPOSAL.vectorHash + "). Ignored.");
                return;
            }
            boolean added = CURRENT_PROPOSAL.confirmedPeers.add(peerId);
            if (!added) {
                log("Duplicate confirmation from " + peerId + " ignored.");
                return;
            }

            int count = CURRENT_PROPOSAL.confirmedPeers.size();
            log("CONFIRM_VECTOR accepted from " + peerId + " ("
                    + count + "/" + CURRENT_PROPOSAL.quorumNeeded + ").");

            if (count >= CURRENT_PROPOSAL.quorumNeeded) {
                commitCurrentProposal(); // quorum reached
            }
        }
    }

    /** Broadcasts COMMIT_VECTOR and promotes pending → confirmed state, embeddings included. */
    private static void commitCurrentProposal() {
        if (CURRENT_PROPOSAL == null) return;

        long version = CURRENT_PROPOSAL.version;
        String hash = CURRENT_PROPOSAL.vectorHash;
        String cid = CURRENT_PROPOSAL.newCid;

        // Apply commit to state
        STATE.applyCommit(); // promote pending->confirmed
        log("Commit applied: v" + version + " (" + hash + ").");

        // Promote embeddings temp -> final
        try {
            FileManager.promoteTempEmbedding(cid);
            log("Embedding promoted to final for CID " + cid);
        } catch (IOException e) {
            log("Failed to promote embedding for " + cid + ": " + e.getMessage());
        }

        // Notify peers
        int ok = 0;
        for (Connection c : CONNECTIONS) {
            try { c.sendCommitVector(version, hash); ok++; } catch (IOException ignored) {}
        }
        log("COMMIT_VECTOR v" + version + " broadcast to " + ok + " peers.");

        CURRENT_PROPOSAL = null;
    }

    // ---------------------------

    static void log(String msg) {
        SwingUtilities.invokeLater(() -> {
            logArea.append(msg + "\n");
            logArea.setCaretPosition(logArea.getDocument().getLength());
        });
    }

    static void incClients() {
        int n = CLIENT_COUNT.incrementAndGet();
        SwingUtilities.invokeLater(() -> clientCountLabel.setText("Connected clients: " + n));
    }

    static void decClients(Connection c) {
        CONNECTIONS.remove(c);
        int n = Math.max(0, CLIENT_COUNT.decrementAndGet());
        SwingUtilities.invokeLater(() -> clientCountLabel.setText("Connected clients: " + n));
    }

    // Legacy helper kept for compatibility (no longer used in the new flow).
    static void broadcastVectorUpdate(long version, List<String> fullVector, String newCid, float[] embedding) {
        int ok = 0;
        for (Connection c : CONNECTIONS) try { c.sendUpdateVector(version, fullVector, newCid, embedding); ok++; } catch (Exception ignored) {}
        log("Vector v" + version + " sent to " + ok + " clients.");
    }

    private static void shutdownIpfs() {
        if (ipfsProcess != null && ipfsProcess.isAlive()) {
            try {
                ProcessBuilder stopPb = new ProcessBuilder("ipfs", "shutdown");
                stopPb.redirectErrorStream(true);
                stopPb.start().waitFor();
                System.out.println("[IPFS] Daemon stopped.");
            } catch (Exception e) {
                ipfsProcess.destroy();
                System.out.println("[IPFS] Daemon force-killed.");
            }
        }
        SwingUtilities.invokeLater(() -> ipfsStatusLabel.setText("IPFS: stopped"));
    }

    // Add near other static methods
    public static void setIpfsStatus(String status) {
        javax.swing.SwingUtilities.invokeLater(() -> ipfsStatusLabel.setText("IPFS: " + status));
    }
}
