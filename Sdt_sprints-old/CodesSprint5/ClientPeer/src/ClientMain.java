import javax.swing.*;

/**
 * ClientMain
 * -------------------------
 * Entry point for the Peer Client application.
 *
 * Responsibilities:
 *  - Ensures a local IPFS daemon is running (with PubSub enabled).
 *  - Starts the GUI for the TCP client.
 *  - Gracefully shuts down the IPFS daemon when the window closes.
 *
 * NOTE:
 *  This class does NOT contain Sprint 5 functionality directly.
 *  Sprint 5 changes are implemented inside:
 *      - TCPClient (heartbeat monitor + new embedding index update)
 *      - LeaderHeartbeatService
 *      - IPFSPubSub (if using HTTP PubSub)
 */
public class ClientMain {

    /** Reference to the spawned IPFS daemon process (if started by this program). */
    private static Process ipfsProcess;

    /**
     * Main program entry.
     * Starts the IPFS daemon if necessary and initializes the TCPClient UI.
     */
    public static void main(String[] args) {
        String apiAddress = "unknown";

        try {
            // Check whether IPFS API is already active
            if (!IPFSInit.isIpfsRunning()) {
                System.out.println("[ClientMain] Starting local IPFS daemon (PubSub enabled)...");

                // Start IPFS with pubsub enabled
                ipfsProcess = IPFSInit.startClientDaemon();

                // Wait up to 25 seconds for IPFS to become responsive
                for (int i = 0; i < 25; i++) {
                    Thread.sleep(1000);
                    if (IPFSInit.isIpfsRunning()) break;
                }

            } else {
                System.out.println("[ClientMain] IPFS daemon already running.");
            }

            // Retrieve the API address for informational display (CLI-based)
            apiAddress = IPFSInit.getApiAddress();
            System.out.println("[ClientMain] IPFS API: " + apiAddress);

        } catch (Exception e) {
            System.err.println("[ClientMain] IPFS start error: " + e.getMessage());
        }

        final String finalApiAddress = apiAddress;

        // Launch the Peer Client UI on the Swing event thread
        SwingUtilities.invokeLater(() -> {
            TCPClient client = new TCPClient();

            // NOTE: TCPClient does not include an "IPFS status" label anymore.
            //       (Was removed earlier in the project evolution)

            // Attach shutdown hook for IPFS daemon
            client.addWindowListener(new java.awt.event.WindowAdapter() {
                @Override
                public void windowClosing(java.awt.event.WindowEvent e) {
                    shutdownIpfs();
                    System.exit(0);
                }
            });

            client.setVisible(true);
        });
    }

    /**
     * Gracefully terminates the IPFS daemon if it was launched by this program.
     * Attempts a clean shutdown via "ipfs shutdown"; falls back to killing the process.
     */
    private static void shutdownIpfs() {
        if (ipfsProcess != null && ipfsProcess.isAlive()) {
            System.out.println("[ClientMain] Stopping local IPFS daemon...");

            try {
                ProcessBuilder stopPb = new ProcessBuilder("ipfs", "shutdown");
                stopPb.redirectErrorStream(true);
                Process stopProcess = stopPb.start();
                stopProcess.waitFor();

                System.out.println("[ClientMain] IPFS daemon stopped successfully.");

            } catch (Exception e) {
                System.out.println("[ClientMain] Shutdown failed — terminating process directly.");
                ipfsProcess.destroy();
            }

        } else {
            System.out.println("[ClientMain] No active IPFS process to stop.");
        }
    }
}
