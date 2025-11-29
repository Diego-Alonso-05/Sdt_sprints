// --- Sprint 5 ---------------------------------------------------------
// LeaderHeartbeatService
//
// Introduced in Sprint 5.
// Purpose:
//   The Leader must periodically broadcast heartbeat messages using PubSub.
//   Peers listen for these messages to detect if the Leader is UP or DOWN.
//   If heartbeats stop arriving for too long, the Peer declares the Leader DOWN.
//
// The Leader sends one heartbeat every 10 seconds (configurable).
// ----------------------------------------------------------------------

import common.AppLog;
import java.util.concurrent.*;

public class LeaderHeartbeatService {

    // --- Sprint 5 ---
    // Interval between heartbeats. Peers tolerate ~15s, so 10s is safe.
    private static final long PERIOD_MS = 10_000; // 10 seconds

    // Scheduler that repeatedly executes the heartbeat task.
    private final ScheduledExecutorService scheduler;

    // Identifier of the Leader (useful if the system supports multiple leaders in future).
    private final String leaderId;

    // PubSub topic where heartbeats are published.
    private final String topic;

    public LeaderHeartbeatService(String leaderId, String topic) {
        this.leaderId = leaderId;
        this.topic = topic;

        // Dedicated background thread for publishing heartbeats.
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "leader-heartbeat-thread");
            t.setDaemon(true);
            return t;
        });
    }

    // --- Sprint 5 ---
    // Starts sending heartbeats at a fixed interval.
    // This runs in the background for the entire lifetime of the Leader.
    public void start() {
        scheduler.scheduleAtFixedRate(() -> {
            try {
                // Heartbeat message structure expected by the clients.
                String msg = "{\"type\":\"HEARTBEAT\",\"leaderId\":\"" + leaderId + "\"}";

                // Publish the heartbeat to PubSub.
                IPFSPubSub.publish(topic, msg);

                // Log so Leader and Peers can see heartbeat activity.
                AppLog.log("Heartbeat sent from Leader");

            } catch (Exception e) {
                AppLog.log("Heartbeat publish error: " + e.getMessage());
            }

        }, 0, PERIOD_MS, TimeUnit.MILLISECONDS);
    }

    // Optional shutdown.
    // Stops the scheduler if the Leader process exits gracefully.
    public void stop() {
        try {
            scheduler.shutdownNow();
        } catch (Exception ignored) {}
    }
}
