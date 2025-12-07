/**
 * ServerMain
 * ----------------
 * Main entry point for the Leader server.
 *
 * Responsibilities:
 *   - Start the TCPServer (file upload endpoint)
 *   - Start the MQTT heartbeat service
 *
 * IPFS has been COMPLETELY REMOVED from the architecture.
 * Therefore:
 *   - No daemon startup
 *   - No API address check
 *   - No shutdown hook
 */
public class ServerMain {

    public static void main(String[] args) {

        // -----------------------------
        // 1. Launch the Leader TCP server GUI
        // -----------------------------
        javax.swing.SwingUtilities.invokeLater(() -> {
            TCPServer server = new TCPServer();
            server.setVisible(true);
        });

        // -----------------------------
        // 2. Start Leader Heartbeat Service (MQTT)
        // -----------------------------
        LeaderHeartbeatService heartbeat =
                new LeaderHeartbeatService("leader-1", "sdt/heartbeat");

        heartbeat.start();

        System.out.println("[Leader] Heartbeat service started on topic sdt/heartbeat.");
        System.out.println("[Leader] ServerMain initialization complete.");
    }
}
