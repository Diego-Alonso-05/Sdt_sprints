import common.AppLog;
import common.MessageBus;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * LeaderHeartbeatService
 * ----------------------
 * Periodically publishes a heartbeat message from the current Leader
 * so that peers can detect that the Leader is alive.
 *
 * This class used to send messages through IPFS PubSub.
 * Now it uses the MQTT-based MessageBus.
 *
 * Usage:
 *
 *   LeaderHeartbeatService hb =
 *       new LeaderHeartbeatService("leader-1", "sdt/heartbeat");
 *   hb.start();
 *
 *   // when the node stops being leader:
 *   hb.stop();
 *
 * Message format (JSON):
 *   {
 *     "type"     : "HEARTBEAT",
 *     "leaderId" : "<leader-id>"
 *   }
 */
public class LeaderHeartbeatService {

    /** Period between heartbeats (milliseconds). */
    private static final long PERIOD_MS = 10_000; // 10 seconds

    private final ScheduledExecutorService scheduler;
    private final String leaderId;
    private final String topic;

    /**
     * @param leaderId logical identifier of this Leader node
     * @param topic    MQTT topic where heartbeats will be published
     *                 (recommended: "sdt/heartbeat")
     */
    public LeaderHeartbeatService(String leaderId, String topic) {
        this.leaderId = leaderId;
        this.topic = topic;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "leader-heartbeat");
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * Starts the periodic heartbeat task.
     * Heartbeats will continue until {@link #stop()} is called.
     */
    public void start() {

        scheduler.scheduleAtFixedRate(() -> {
            try {
                String msg = "{\"type\":\"HEARTBEAT\",\"leaderId\":\""
                        + leaderId + "\"}";

                AppLog.log("[HB] Publishing heartbeat to topic: " + topic);
                AppLog.log("[HB] Payload: " + msg);

                // Send heartbeat via MQTT
                MessageBus.publish(topic, msg);

            } catch (Exception e) {
                AppLog.log("Heartbeat publish error: " + e.getMessage());
                e.printStackTrace();
            }

        }, 0, PERIOD_MS, TimeUnit.MILLISECONDS);
    }

    /**
     * Stops the heartbeat scheduler.
     * Should be called when this node stops acting as Leader.
     */
    public void stop() {
        try {
            scheduler.shutdownNow();
        } catch (Exception ignored) {
        }
    }
}
