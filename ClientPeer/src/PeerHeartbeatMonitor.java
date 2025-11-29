// --- Sprint 5 ---
// PeerHeartbeatMonitor
// Added in Sprint 5 to detect when the Leader goes DOWN
// and when it comes back UP. The peer uses this to know
// if the system is still coordinated or if the Leader failed.

import java.util.concurrent.*;

public class PeerHeartbeatMonitor {

    // Time (in ms) the peer tolerates without receiving a heartbeat.
    // After this threshold, the Leader is considered DOWN.
    private static final long TIMEOUT_MS = 15_000;

    // Scheduler used to periodically check the heartbeat timeout.
    private final ScheduledExecutorService scheduler;

    // Timestamp of the last received heartbeat.
    private volatile long lastHeartbeat = System.currentTimeMillis();

    // True if the Leader is currently considered DOWN.
    private volatile boolean leaderDown = false;

    // Callback executed when the Leader is considered DOWN.
    private final Runnable onLeaderDown;

    // Callback executed when the Leader sends heartbeats again.
    private final Runnable onLeaderUp;

    public PeerHeartbeatMonitor(Runnable onLeaderDown, Runnable onLeaderUp) {
        this.onLeaderDown = onLeaderDown;
        this.onLeaderUp = onLeaderUp;

        // Background monitoring thread (daemon).
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "peer-heartbeat-monitor");
            t.setDaemon(true);
            return t;
        });
    }

    // --- Sprint 5 ---
    // Called whenever a heartbeat arrives.
    // Updates the timestamp and, if the Leader was DOWN,
    // marks it as UP again.
    public void recordHeartbeat() {
        lastHeartbeat = System.currentTimeMillis();

        if (leaderDown) {
            leaderDown = false;
            onLeaderUp.run();   // Leader is back
        }
    }

    // --- Sprint 5 ---
    // Starts the periodic monitoring loop.
    // Every second, checks if too much time passed since the last heartbeat.
    // If so, declares the Leader DOWN.
    public void start() {
        scheduler.scheduleAtFixedRate(() -> {
            long now = System.currentTimeMillis();

            // Leader timed out → mark as DOWN
            if (!leaderDown && (now - lastHeartbeat > TIMEOUT_MS)) {
                leaderDown = true;
                onLeaderDown.run();   // Trigger DOWN callback
            }

        }, 0, 1_000, TimeUnit.MILLISECONDS); // Runs every 1s
    }

    // Stops the monitoring task.
    public void stop() {
        scheduler.shutdownNow();
    }
}
