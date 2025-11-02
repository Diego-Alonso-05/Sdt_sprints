package leader;

import javax.swing.*;
import java.awt.*;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * TCPServer
 * ----------
 * This class represents the Leader (Server) node.
 * It handles:
 *  - Starting/stopping the TCP server.
 *  - Accepting incoming client connections.
 *  - Displaying logs and the number of connected clients.
 *  - Sending broadcast messages to all connected clients.
 *  - Propagating vector and embedding updates.
 */
public class TCPServer extends JFrame {

    private static JTextArea logArea;
    private static JLabel clientCountLabel;
    private JButton startButton, stopButton, sendMsgButton;
    private JTextField messageField;

    private ServerSocket serverSocket;
    private Thread acceptThread;
    private volatile boolean running = false;

    // List of all active client connections
    static final List<Connection> CONNECTIONS = new CopyOnWriteArrayList<>();
    static final AtomicInteger CLIENT_COUNT = new AtomicInteger(0);

    // Leader state (document vector + version control)
    static final LeaderState STATE = new LeaderState();

    public TCPServer() {
        super("Leader - Server (IPFS + Broadcast)");

        // Create GUI components
        logArea = new JTextArea(18, 70);
        logArea.setEditable(false);
        JScrollPane scrollPane = new JScrollPane(logArea);

        clientCountLabel = new JLabel("Connected clients: 0");

        startButton = new JButton("Start Server");
        stopButton  = new JButton("Stop Server");
        stopButton.setEnabled(false);

        // Action listeners for the control buttons
        startButton.addActionListener(e -> startServer());
        stopButton.addActionListener(e -> stopServer());

        messageField = new JTextField(40);
        sendMsgButton = new JButton("Send to all");

        sendMsgButton.addActionListener(e -> {
            String msg = messageField.getText().trim();
            if (!msg.isEmpty()) {
                broadcastToAll(msg);
                messageField.setText("");
            }
        });

        // Layout setup
        JPanel topPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        topPanel.add(startButton);
        topPanel.add(stopButton);
        topPanel.add(Box.createHorizontalStrut(10));
        topPanel.add(clientCountLabel);

        JPanel bottomPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        bottomPanel.add(new JLabel("Message:"));
        bottomPanel.add(messageField);
        bottomPanel.add(sendMsgButton);

        setLayout(new BorderLayout());
        add(topPanel, BorderLayout.NORTH);
        add(scrollPane, BorderLayout.CENTER);
        add(bottomPanel, BorderLayout.SOUTH);

        setDefaultCloseOperation(EXIT_ON_CLOSE);
        pack();
        setLocationRelativeTo(null);
        setVisible(true);
    }

    /** Starts the TCP server and begins accepting client connections. */
    private void startServer() {
        if (running) return;
        try {
            serverSocket = new ServerSocket(5000);
            running = true;
            log("Leader server is listening on port 5000...");
            startButton.setEnabled(false);
            stopButton.setEnabled(true);

            // Thread to continuously accept incoming clients
            acceptThread = new Thread(() -> {
                while (running) {
                    try {
                        Socket socket = serverSocket.accept();
                        log("New client connected from: " + socket.getInetAddress().getHostAddress());
                        Connection connection = new Connection(socket);
                        CONNECTIONS.add(connection);
                        connection.start();
                    } catch (IOException e) {
                        if (running) log("Error in accept(): " + e.getMessage());
                    }
                }
                log("Accept thread terminated.");
            }, "accept-thread");

            acceptThread.start();

        } catch (IOException e) {
            log("Unable to open port 5000: " + e.getMessage());
        }
    }

    /** Stops the TCP server and closes all active client connections. */
    private void stopServer() {
        running = false;
        try {
            if (serverSocket != null && !serverSocket.isClosed()) serverSocket.close();
        } catch (IOException ignored) {}

        // Close all active client connections
        for (Connection connection : CONNECTIONS) {
            try {
                connection.closeQuietly();
            } catch (Exception ignored) {}
        }

        CONNECTIONS.clear();
        clientCountLabel.setText("Connected clients: 0");

        startButton.setEnabled(true);
        stopButton.setEnabled(false);
        log("Server stopped.");
    }

    /**
     * Sends a broadcast message to all connected clients.
     * Each client receives the message with opcode 1.
     */
    private void broadcastToAll(String msg) {
        int success = 0, failed = 0;
        for (Connection connection : CONNECTIONS) {
            try {
                connection.sendBroadcast(msg);
                success++;
            } catch (Exception ex) {
                failed++;
            }
        }
        log("Broadcast sent to " + success + " clients" + 
            (failed > 0 ? (" (failed: " + failed + ")") : "") + ".");
    }

    /** Logs a message to the GUI console in a thread-safe way. */
    static void log(String msg) {
        SwingUtilities.invokeLater(() -> {
            logArea.append(msg + "\n");
            logArea.setCaretPosition(logArea.getDocument().getLength());
        });
    }

    /** Increments the count of connected clients (called from Connection). */
    static void incClients() {
        int n = CLIENT_COUNT.incrementAndGet();
        SwingUtilities.invokeLater(() -> clientCountLabel.setText("Connected clients: " + n));
    }

    /** Decrements the client count and updates the label. */
    static void decClients(Connection connection) {
        CONNECTIONS.remove(connection);
        int n = CLIENT_COUNT.decrementAndGet();
        if (n < 0) {
            CLIENT_COUNT.set(0);
            n = 0;
        }
        final int updatedCount = n;
        SwingUtilities.invokeLater(() -> clientCountLabel.setText("Connected clients: " + updatedCount));
    }

    /** Broadcasts an updated document vector and embeddings to all clients. */
    static void broadcastVectorUpdate(long version, List<String> fullVector, String newCid, float[] embedding) {
        int success = 0, failed = 0;
        for (Connection connection : CONNECTIONS) {
            try {
                connection.sendVectorUpdate(version, fullVector, newCid, embedding);
                success++;
            } catch (Exception e) {
                failed++;
            }
        }
        log("Vector v" + version + " propagated to " + success + " clients" +
            (failed > 0 ? (" (failed: " + failed + ")") : "") + ".");
    }

    /** Main method: starts the GUI for the Leader server. */
    public static void main(String[] args) {
        SwingUtilities.invokeLater(TCPServer::new);
    }
}
