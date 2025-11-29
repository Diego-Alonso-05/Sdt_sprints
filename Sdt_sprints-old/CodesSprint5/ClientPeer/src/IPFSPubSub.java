import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;

/**
 * Minimal PubSub wrapper — does NOT modify rest of system.
 * Provides:
 *   - publish(topic, json)
 *   - subscribe(topic, handler)
 *
 * Works using IPFS CLI so no new dependencies are needed.
 */
public class IPFSPubSub {

    /** Publish JSON message to PubSub topic */
    public static void publish(String topic, String json) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(
                "ipfs", "pubsub", "publish", topic, json
        );
        pb.redirectErrorStream(true);
        Process p = pb.start();
        p.waitFor();
    }

    /** Subscribe to a topic and feed the handler with each incoming message */
    public static void subscribe(String topic, Consumer<String> handler) {
        Thread t = new Thread(() -> {
            while (true) {
                try {
                    ProcessBuilder pb = new ProcessBuilder(
                            "ipfs", "pubsub", "subscribe", topic
                    );
                    pb.redirectErrorStream(true);
                    Process p = pb.start();

                    try (BufferedReader br = new BufferedReader(
                            new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {

                        String line;
                        while ((line = br.readLine()) != null) {
                            String msg = line.trim();
                            if (!msg.isEmpty()) {
                                handler.accept(msg);
                            }
                        }
                    }

                    // If subscribe process ends, restart it
                    Thread.sleep(1000);

                } catch (Exception e) {
                    // Retry after error
                    try { Thread.sleep(2000); } catch (InterruptedException ignored) {}
                }
            }
        }, "IPFS-PubSub-Subscriber-" + topic);

        t.setDaemon(true);
        t.start();
    }
}
