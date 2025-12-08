import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

public class IPFSInit_Client {

    /** Checks if the local IPFS API is responding. */
    public static boolean isIpfsRunning() {
        try {
            URL url = new URL("http://127.0.0.1:5001/api/v0/id");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(1500);
            conn.setReadTimeout(1500);
            if (conn.getResponseCode() == 200) return true;
        } catch (IOException ignored) {}
        return false;
    }

    /** Gets IPFS API address from config. */
    public static String getApiAddress() {
        try {
            ProcessBuilder pb = new ProcessBuilder("ipfs", "config", "Addresses.API");
            pb.redirectErrorStream(true);
            Process proc = pb.start();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8))) {
                String address = reader.readLine();
                if (address != null) {
                    address = address.replace("\"", "").trim();
                    if (!address.isEmpty()) return address;
                }
            }
        } catch (IOException ignored) {}
        return "unknown";
    }

    /** Launches the client IPFS daemon with PubSub enabled. */
    public static Process startClientDaemon() throws IOException {
        boolean win = System.getProperty("os.name").toLowerCase().contains("win");
        ProcessBuilder pb = win
                ? new ProcessBuilder("cmd.exe", "/c", "ipfs", "daemon", "--enable-pubsub-experiment")
                : new ProcessBuilder("ipfs", "daemon", "--enable-pubsub-experiment");
        pb.redirectErrorStream(true);
        Process p = pb.start();

        Thread t = new Thread(() -> {
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    if (line.contains("API server listening on"))
                        System.out.println("[IPFS] " + line);
                    if (line.contains("Daemon is ready")) {
                        System.out.println("[IPFS] Client daemon ready.");
                        break;
                    }
                }
            } catch (IOException ignored) {}
        }, "ipfs-client-log");
        t.setDaemon(true);
        t.start();

        return p;
    }
}
