import java.net.URL;
import java.net.HttpURLConnection;

/**
 * ServerMain
 * ----------------
 * Main entry point for the Leader server.
 * Handles:
 *  - IPFS daemon startup & readiness check
 *  - Launching the TCPServer GUI
 *  - Graceful cleanup on exit
 */
public class ServerMain {

    private static Process ipfsProcess;

    public static void main(String[] args) {
        String apiAddress = "unknown";

        try {
            // 1. Check IPFS status or start it
            if (!IPFSInit.isIpfsRunning()) {
                System.out.println("[IPFS] Starting local daemon...");
                ipfsProcess = IPFSInit.startServerDaemon();

                for (int i = 0; i < 25; i++) { // wait up to 25s
                    Thread.sleep(1000);
                    if (IPFSInit.isIpfsRunning()) break;
                }
            } else {
                System.out.println("[IPFS] Already running.");
            }

            // 2. Retrieve IPFS API address for display
            apiAddress = IPFSInit.getApiAddress();
            System.out.println("[IPFS] API listening at: " + apiAddress);

        } catch (Exception e) {
            System.err.println("IPFS start error: " + e.getMessage());
        }

        // 3. Launch the Leader GUI
        final String finalApiAddress = apiAddress;
        javax.swing.SwingUtilities.invokeLater(() -> {
            TCPServer server = new TCPServer();
            if (IPFSInit.isIpfsRunning()) {
                TCPServer.setIpfsStatus("🟢 " + finalApiAddress);
            } else {
                TCPServer.setIpfsStatus("not running");
            }
            server.setVisible(true);
        });

        // 4. Gracefully stop IPFS on JVM exit
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                if (ipfsProcess != null && ipfsProcess.isAlive()) {
                    ProcessBuilder stopPb = new ProcessBuilder("ipfs", "shutdown");
                    stopPb.redirectErrorStream(true);
                    stopPb.start().waitFor();
                    System.out.println("[IPFS] Daemon stopped.");
                }
            } catch (Exception e) {
                if (ipfsProcess != null) ipfsProcess.destroy();
                System.out.println("[IPFS] Daemon force-killed.");
            }
        }));
    }
}
