import javax.swing.*;

/**
 * ClientMain
 * ---------------
 * Main entry point for the Peer Client.
 * - Starts local IPFS daemon (PubSub enabled)
 * - Launches the TCPClient GUI
 * - Gracefully shuts down IPFS on exit
 */
public class ClientMain {

    private static Process ipfsProcess;

    public static void main(String[] args) {
        String apiAddress = "unknown";

        try {
            // Start local IPFS daemon if not already running
            if (!IPFSInit.isIpfsRunning()) {
                System.out.println("[ClientMain] Starting local IPFS daemon (PubSub enabled)...");
                ipfsProcess = IPFSInit.startClientDaemon();

                // Wait up to 25 seconds for IPFS to start responding
                for (int i = 0; i < 25; i++) {
                    Thread.sleep(1000);
                    if (IPFSInit.isIpfsRunning()) break;
                }
            } else {
                System.out.println("[ClientMain] IPFS daemon already running.");
            }

            // Retrieve API address for display
            apiAddress = IPFSInit.getApiAddress();
            System.out.println("[ClientMain] IPFS API: " + apiAddress);

        } catch (Exception e) {
            System.err.println("[ClientMain] IPFS start error: " + e.getMessage());
        }

        final String finalApiAddress = apiAddress;

        SwingUtilities.invokeLater(() -> {
            TCPClient client = new TCPClient();
            TCPClient.setIpfsStatus(IPFSInit.isIpfsRunning()
                    ? "🟢 " + finalApiAddress
                    : "not running");

            // Stop IPFS on GUI close
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

    /** Gracefully stops the local IPFS daemon when the GUI closes. */
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
                System.out.println("[ClientMain] Shutdown via API failed — killing process directly.");
                ipfsProcess.destroy();
            }
        } else {
            System.out.println("[ClientMain] No active IPFS process to stop.");
        }
    }
}
