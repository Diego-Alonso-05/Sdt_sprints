import common.AppLog;

import java.util.Timer;
import java.util.TimerTask;
import java.util.function.Consumer;

/**
 * PeerHeartbeatMonitor
 * --------------------
 * Monitors leader liveness based on incoming HEARTBEAT messages.
 *
 * Leader sends (on the same MQTT topic as the rest of the control traffic):
 *   { "type": "HEARTBEAT", "leaderId": "<id>" }
 *
 * TCPClient is responsible for:
 *   - subscribing to the MQTT topic
 *   - calling handleHeartbeat(json) when a HEARTBEAT is received
 *
 * This class:
 *   - keeps a timeout timer
 *   - resets the timer on every heartbeat
 *   - if no heartbeat is seen before TIMEOUT_MS, it calls onLeaderTimeout,
 *     which should usually start a Raft election.
 */
public class PeerHeartbeatMonitor {

    /** Timeout after the last heartbeat before considering the leader dead. */
    private static final long TIMEOUT_MS = 20_000; // 20 seconds

    /** Callback to run when leader is considered down. */
    private final Consumer<Void> onLeaderTimeout;

    private final Timer timer;
    private TimerTask currentTask;

    /**
     * @param onLeaderTimeout callback invoked when no heartbeat has been seen
     *                        for TIMEOUT_MS. Typically: v -> raftService.startElection()
     */
    public PeerHeartbeatMonitor(Consumer<Void> onLeaderTimeout) {
        this.onLeaderTimeout = onLeaderTimeout;
        this.timer = new Timer(true);

        // Start the initial timeout (no heartbeat seen yet).
        resetTimeout();
    }
    public void resetTimeoutFromOutside() {
        resetTimeout();
    }

    // -------------------------------------------------------------------------
    // Called by TCPClient when a HEARTBEAT message is received
    // -------------------------------------------------------------------------
    /**
     * Should be called by TCPClient whenever a HEARTBEAT arrives over MQTT.
     *
     * Example in TCPClient:
     *   if (json.contains("\"type\":\"HEARTBEAT\"") && heartbeatMonitor != null) {
     *       heartbeatMonitor.handleHeartbeat(json);
     *   }
     */
    public void handleHeartbeat(String json) {
        AppLog.log("[Peer] Heartbeat received: " + json);
        resetTimeout();
    }

    // -------------------------------------------------------------------------
    // RESET TIMEOUT EACH TIME A HEARTBEAT ARRIVES
    // -------------------------------------------------------------------------
    private synchronized void resetTimeout() {
        if (currentTask != null) {
            currentTask.cancel();
        }

        currentTask = new TimerTask() {
            @Override
            public void run() {
                AppLog.log("[Peer] Leader heartbeat timeout → starting election.");
                onLeaderTimeout.accept(null);
            }
        };

        timer.schedule(currentTask, TIMEOUT_MS);
    }

    // -------------------------------------------------------------------------
    // STOP MONITOR
    // -------------------------------------------------------------------------
    public void stop() {
        if (currentTask != null) {
            currentTask.cancel();
        }
        timer.cancel();
    }
}
