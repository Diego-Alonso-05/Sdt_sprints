import common.AppLog;
import common.MessageBus;

import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * RaftElectionService (MQTT version)
 * ----------------------------------
 * Same behaviour as Sprint-6 RAFT but:
 *  - Uses MessageBus (MQTT) instead of IPFS PubSub
 *  - No IPFS peer count; majority must be provided externally
 *
 * Required usage from ClientMain:
 *   raft.setPeerCount(numberOfPeers);
 *   raft.startElection();
 */
public class RaftElectionService {

    private enum Role { FOLLOWER, CANDIDATE, LEADER }

    private volatile Role role = Role.FOLLOWER;

    private volatile int currentTerm = 0;
    private volatile String votedFor = null;
    private volatile String leaderId = null;

    private final Supplier<String> peerIdSupplier;
    private final Supplier<Long> versionSupplier;

    private ScheduledExecutorService hbScheduler;

    private volatile int votesReceived = 0;

    /** Must be set from ClientMain */
    private volatile int majority = 1;

    public RaftElectionService(Supplier<String> peerIdSupplier,
                               Supplier<Long> versionSupplier) {
        this.peerIdSupplier = peerIdSupplier;
        this.versionSupplier = versionSupplier;
    }

    // -------------------------------------------------------------------------
    // Allow ClientMain to tell RAFT how many peers exist
    // -------------------------------------------------------------------------
    public void setPeerCount(int count) {
        this.majority = Math.max(1, (count / 2) + 1);
        AppLog.log("[RAFT] Majority threshold = " + majority);
    }

    // -------------------------------------------------------------------------
    // Start Election
    // -------------------------------------------------------------------------
    public synchronized void startElection() {
        if (role == Role.LEADER || role == Role.CANDIDATE) return;

        role = Role.CANDIDATE;
        currentTerm++;
        votedFor = peerIdSupplier.get();
        votesReceived = 1;

        AppLog.log("[RAFT] Starting election, term " + currentTerm);

        sendRequestVote();
    }

    // -------------------------------------------------------------------------
    // Handle RequestVote
    // -------------------------------------------------------------------------
    public void handleRequestVote(String json) {
        try {
            int term = Integer.parseInt(extract(json, "term"));
            String candidateId = extract(json, "candidateId");
            long candidateVersion = Long.parseLong(extract(json, "vectorVersion"));

            String selfId = peerIdSupplier.get();
            if (candidateId.equals(selfId)) return;

            synchronized (this) {

                if (term < currentTerm) return;

                if (term > currentTerm) {
                    currentTerm = term;
                    role = Role.FOLLOWER;
                    votedFor = null;
                }

                boolean canVote = (votedFor == null || votedFor.equals(candidateId));
                boolean upToDate = versionSupplier.get() <= candidateVersion;

                boolean grant = canVote && upToDate;

                if (grant) votedFor = candidateId;

                sendVoteResponse(candidateId, grant);
            }

        } catch (Exception ignored) {}
    }

    // -------------------------------------------------------------------------
    // Handle VoteResponse
    // -------------------------------------------------------------------------
    public void handleVoteResponse(String json) {
        try {
            int term = Integer.parseInt(extract(json, "term"));
            boolean granted = Boolean.parseBoolean(extract(json, "voteGranted"));

            synchronized (this) {
                if (term != currentTerm) return;
                if (role != Role.CANDIDATE) return;

                if (granted) {
                    votesReceived++;
                    if (votesReceived >= majority) {
                        becomeLeader();
                    }
                }
            }

        } catch (Exception ignored) {}
    }

    // -------------------------------------------------------------------------
    // Transition to Leader
    // -------------------------------------------------------------------------
    private void becomeLeader() {
        role = Role.LEADER;
        leaderId = peerIdSupplier.get();
        votedFor = null;

        AppLog.log("[RAFT] We won the election! New Leader = " + leaderId);

        sendNewLeader();
        startHeartbeat();
    }

    // -------------------------------------------------------------------------
    // Handle NewLeader
    // -------------------------------------------------------------------------
    public void handleNewLeader(String json) {
        try {
            int term = Integer.parseInt(extract(json, "term"));
            String newLeaderId = extract(json, "leaderId");

            synchronized (this) {
                if (term < currentTerm) return;

                currentTerm = term;
                leaderId = newLeaderId;

                if (!peerIdSupplier.get().equals(newLeaderId)) {
                    role = Role.FOLLOWER;
                    votedFor = null;
                    stopHeartbeat();
                }
            }

            AppLog.log("[RAFT] Following Leader = " + newLeaderId);

        } catch (Exception ignored) {}
    }

    // -------------------------------------------------------------------------
    // Outgoing RAFT messages
    // -------------------------------------------------------------------------
    private void sendRequestVote() {
        long version = versionSupplier.get();
        String self = peerIdSupplier.get();

        String msg = "{\"type\":\"RAFT_REQUEST_VOTE\",\"term\":" + currentTerm +
                ",\"candidateId\":\"" + self +
                "\",\"vectorVersion\":" + version + "}";

        MessageBus.publish("sdt/vector", msg);
        AppLog.log("[RAFT] RequestVote sent");
    }

    private void sendVoteResponse(String candidateId, boolean granted) {
        String self = peerIdSupplier.get();

        String msg = "{\"type\":\"RAFT_VOTE_RESPONSE\",\"term\":" + currentTerm +
                ",\"voteGranted\":" + granted +
                ",\"fromId\":\"" + self + "\"}";

        MessageBus.publish("sdt/vector", msg);
    }

    private void sendNewLeader() {
        String self = peerIdSupplier.get();

        String msg = "{\"type\":\"RAFT_NEW_LEADER\",\"term\":" + currentTerm +
                ",\"leaderId\":\"" + self + "\"}";

        MessageBus.publish("sdt/vector", msg);
    }

    // -------------------------------------------------------------------------
    // Heartbeats
    // -------------------------------------------------------------------------
    private void startHeartbeat() {
        stopHeartbeat();

        hbScheduler = Executors.newSingleThreadScheduledExecutor();
        hbScheduler.scheduleAtFixedRate(() -> {
            String id = peerIdSupplier.get();
            String msg = "{\"type\":\"HEARTBEAT\",\"leaderId\":\"" + id + "\"}";
            MessageBus.publish("sdt/vector", msg);
        }, 0, 5, TimeUnit.SECONDS);

        AppLog.log("[RAFT] Heartbeats started");
    }

    private void stopHeartbeat() {
        if (hbScheduler != null) {
            hbScheduler.shutdownNow();
            hbScheduler = null;
        }
    }

    // -------------------------------------------------------------------------
    // JSON helper
    // -------------------------------------------------------------------------
    private String extract(String json, String key) {
        try {
            String p1 = "\"" + key + "\":\"";
            int i = json.indexOf(p1);
            if (i != -1) {
                int j = json.indexOf("\"", i + p1.length());
                return json.substring(i + p1.length(), j);
            }
            String p2 = "\"" + key + "\":";
            i = json.indexOf(p2);
            if (i != -1) {
                int start = i + p2.length();
                int j = start;
                while (j < json.length() &&
                        (Character.isDigit(json.charAt(j)) || json.charAt(j) == '-')) j++;
                return json.substring(start, j);
            }
        } catch (Exception ignored) {}
        return null;
    }
}
