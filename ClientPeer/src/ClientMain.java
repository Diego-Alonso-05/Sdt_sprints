import javax.swing.*;

/**
 * ClientMain
 * -------------------------
 * Entry point for the Peer Client application.
 *
 * Responsibilities:
 *  - Start the TCPClient GUI
 *  - Create RAFT election service (using MQTT)
 *  - Create heartbeat monitor
 *
 * MQTT topics:
 *   - All control traffic (RAFT, vector messages, heartbeats)
 *     travels over "sdt/vector"
 */
public class ClientMain {

    public static void main(String[] args) {

        SwingUtilities.invokeLater(() -> {

            // -----------------------------------------
            // 1. Start TCPClient (GUI + MQTT listener)
            // -----------------------------------------
            TCPClient client = new TCPClient();
            client.setVisible(true);

            // -----------------------------------------
            // 2. Create RAFT service (using MQTT)
            // -----------------------------------------
            RaftElectionService raft = new RaftElectionService(
                    client::getPeerId,         // supplier for peer id
                    client::getLocalVersion    // supplier for current vector version
            );

            client.setRaftService(raft);

            // -----------------------------------------
            // 3. Heartbeat monitor (detect leader failure)
            // -----------------------------------------
            PeerHeartbeatMonitor hb = new PeerHeartbeatMonitor(ignored -> {
                System.out.println("[ClientMain] Leader heartbeat timeout → starting election");
                raft.startElection();
            });

            client.setHeartbeatMonitor(hb);

            System.out.println("[ClientMain] Peer fully initialized.");
        });
    }
}
