// --- Sprint 6 -------------------------------------------------------------
// RaftElectionService
//
// Added in Sprint 6. This class implements a simplified leader election
// mechanism based on the Raft consensus algorithm. When a peer detects
// the absence of the current Leader (through the PeerHeartbeatMonitor), it
// triggers an election. Peers exchange RequestVote, VoteResponse and
// NewLeader messages via IPFS PubSub to agree on a new Leader. The elected
// Leader then begins broadcasting heartbeats. This service does not
// replicate log entries; it assumes that all peers maintain the same
// confirmed vector state prior to the Leader failing. The goal is to
// ensure that a new Leader is chosen and heartbeats resume without
// modifying existing system behaviour.
//
// Usage:
//   RaftElectionService raft = new RaftElectionService(
//           () -> peerIdField.getText(),      // supplies the local peer ID
//           "sdt-updates",                   // PubSub topic used by the project
//           () -> confirmedVersion            // supplies the local confirmed vector version
//   );
//   // On Leader DOWN:
//   raft.startElection();
//   // In processPubSubMessage():
//   if (json.contains("\"type\":\"RAFT_REQUEST_VOTE\"")) raft.handleRequestVote(json);
//   if (json.contains("\"type\":\"RAFT_VOTE_RESPONSE\"")) raft.handleVoteResponse(json);
//   if (json.contains("\"type\":\"RAFT_NEW_LEADER\"")) raft.handleNewLeader(json);
//
// NOTE: This class relies on the IPFS command line to determine the current
// number of PubSub peers. If this fails, a majority of one is assumed.
//

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

// --- Sprint 6 ---
public class RaftElectionService {

    // Raft roles
    private enum Role { FOLLOWER, CANDIDATE, LEADER }

    // Current role of this peer
    private volatile Role role = Role.FOLLOWER;

    // Current term number
    private volatile int currentTerm = 0;

    // Peer ID this node voted for in current term (null if none)
    private volatile String votedFor = null;

    // ID of the current Leader (null if none)
    private volatile String leaderId = null;

    // Supplier for obtaining our own peer ID at runtime
    private final Supplier<String> peerIdSupplier;

    // PubSub topic used for Raft messages
    private final String topic;

    // Supplier that provides the local confirmed vector version
    private final Supplier<Long> versionSupplier;

    // Scheduled executor for sending heartbeat messages when Leader
    // (initialized lazily)
    private ScheduledExecutorService hbScheduler;

    // Votes received in current election
    private volatile int votesReceived = 0;

    // Majority threshold (computed at election start)
    private volatile int majority = 1;

    // --- Sprint 6 ---
    public RaftElectionService(Supplier<String> peerIdSupplier,
                               String topic,
                               Supplier<Long> versionSupplier) {
        this.peerIdSupplier = peerIdSupplier;
        this.topic = topic;
        this.versionSupplier = versionSupplier;
        // Heartbeat scheduler will be created when this peer becomes leader
        this.hbScheduler = null;
    }

    /**
     * Initiates a new election. Increments the term, votes for self
     * and broadcasts a RequestVote message to all peers. If already
     * running an election or acting as Leader, the call is ignored.
     */
    // --- Sprint 6 ---
    public synchronized void startElection() {
        // Ignore if already Leader or Candidate
        if (role == Role.LEADER || role == Role.CANDIDATE) {
            return;
        }
        role = Role.CANDIDATE;
        currentTerm++;
        votedFor = peerIdSupplier.get();
        votesReceived = 1; // vote for self
        // Determine majority based on current PubSub peers
        majority = Math.max(1, (getPeerCount() / 2) + 1);
        sendRequestVote();
    }

    /**
     * Handles an incoming RequestVote message. Grants vote if the
     * candidate's log (vector version) is at least as up-to-date and
     * if we haven't voted yet in this term. If a higher term is
     * observed, the node steps down to FOLLOWER.
     */
    // --- Sprint 6 ---
    public void handleRequestVote(String json) {
        try {
            int term = Integer.parseInt(extract(json, "term"));
            String candidateId = extract(json, "candidateId");
            String versionStr = extract(json, "vectorVersion");
            long candidateVersion = versionStr == null ? 0L : Long.parseLong(versionStr);

            // Ignore requests from ourselves
            String selfId = peerIdSupplier.get();
            if (candidateId != null && candidateId.equals(selfId)) {
                return;
            }
            synchronized (this) {
                if (term < currentTerm) {
                    return; // stale term
                }
                if (term > currentTerm) {
                    // New term discovered → become follower
                    currentTerm = term;
                    role = Role.FOLLOWER;
                    votedFor = null;
                }
                boolean canVote = (votedFor == null || votedFor.equals(candidateId));
                boolean upToDate = versionSupplier.get() <= candidateVersion;
                if (canVote && upToDate) {
                    votedFor = candidateId;
                    sendVoteResponse(candidateId, true);
                } else {
                    sendVoteResponse(candidateId, false);
                }
            }
        } catch (Exception ignored) {}
    }

    /**
     * Handles an incoming VoteResponse message. If a majority of votes
     * are collected for the current term, the node becomes Leader and
     * broadcasts a NewLeader message, then starts sending heartbeats.
     */
    // --- Sprint 6 ---
    public void handleVoteResponse(String json) {
        try {
            int term = Integer.parseInt(extract(json, "term"));
            String grantedStr = extract(json, "voteGranted");
            boolean granted = Boolean.parseBoolean(grantedStr);
            synchronized (this) {
                if (term != currentTerm || role != Role.CANDIDATE) {
                    return;
                }
                if (granted) {
                    votesReceived++;
                    if (votesReceived >= majority) {
                        // Become Leader
                        role = Role.LEADER;
                        leaderId = peerIdSupplier.get();
                        votedFor = null;
                        sendNewLeader();
                        startHeartbeat();
                    }
                }
            }
        } catch (Exception ignored) {}
    }

    /**
     * Handles a NewLeader announcement. Updates the current term if
     * necessary, steps down to FOLLOWER (if not the announced Leader)
     * and stops sending heartbeats. Also clears our vote.
     */
    // --- Sprint 6 ---
    public void handleNewLeader(String json) {
        try {
            int term = Integer.parseInt(extract(json, "term"));
            String newLeaderId = extract(json, "leaderId");
            synchronized (this) {
                if (term < currentTerm) {
                    return;
                }
                currentTerm = term;
                leaderId = newLeaderId;
                String selfId = peerIdSupplier.get();
                if (!selfId.equals(newLeaderId)) {
                    role = Role.FOLLOWER;
                    votedFor = null;
                    stopHeartbeat();
                }
            }
            common.AppLog.log("[RAFT] Following new leader: " + newLeaderId + " term=" + term);
        } catch (Exception ignored) {}
    }

    // Send a RequestVote message to all peers
    private void sendRequestVote() {
        long version = versionSupplier.get();
        String selfId = peerIdSupplier.get();
        String msg = "{\"type\":\"RAFT_REQUEST_VOTE\",\"term\":" + currentTerm +
                ",\"candidateId\":\"" + selfId + "\",\"vectorVersion\":" + version + "}";
        try {
            IPFSPubSub.publish(topic, msg);
            common.AppLog.log("[RAFT] RequestVote sent term=" + currentTerm + " candidate=" + selfId);
        } catch (Exception e) {
            common.AppLog.log("[RAFT] Failed to send RequestVote: " + e.getMessage());
        }
    }

    // Send a VoteResponse message to the candidate
    private void sendVoteResponse(String candidateId, boolean granted) {
        String selfId = peerIdSupplier.get();
        String msg = "{\"type\":\"RAFT_VOTE_RESPONSE\",\"term\":" + currentTerm +
                ",\"voteGranted\":" + granted + ",\"fromId\":\"" + selfId + "\"}";
        try {
            IPFSPubSub.publish(topic, msg);
            common.AppLog.log("[RAFT] Vote response to " + candidateId + " granted=" + granted);
        } catch (Exception e) {
            common.AppLog.log("[RAFT] Failed to send VoteResponse: " + e.getMessage());
        }
    }

    // Announce the new Leader to all peers
    private void sendNewLeader() {
        String selfId = peerIdSupplier.get();
        String msg = "{\"type\":\"RAFT_NEW_LEADER\",\"term\":" + currentTerm +
                ",\"leaderId\":\"" + selfId + "\"}";
        try {
            IPFSPubSub.publish(topic, msg);
            common.AppLog.log("[RAFT] New leader announced " + selfId + " term=" + currentTerm);
        } catch (Exception e) {
            common.AppLog.log("[RAFT] Failed to send NewLeader: " + e.getMessage());
        }
    }

    // Start periodic heartbeat messages when acting as Leader
    private void startHeartbeat() {
        synchronized (this) {
            if (hbScheduler != null && !hbScheduler.isShutdown()) {
                return;
            }
            hbScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "raft-heartbeat-thread");
                t.setDaemon(true);
                return t;
            });
            hbScheduler.scheduleAtFixedRate(() -> {
                String selfId = peerIdSupplier.get();
                String msg = "{\"type\":\"HEARTBEAT\",\"leaderId\":\"" + selfId + "\"}";
                try {
                    IPFSPubSub.publish(topic, msg);
                } catch (Exception e) {
                    // ignore heartbeat errors
                }
            }, 0, 10_000, TimeUnit.MILLISECONDS);
        }
    }

    // Stop sending heartbeats (called when stepping down)
    private void stopHeartbeat() {
        synchronized (this) {
            if (hbScheduler != null) {
                try {
                    hbScheduler.shutdownNow();
                } catch (Exception ignored) {}
                hbScheduler = null;
            }
        }
    }

    // Get current number of PubSub peers (including self). Uses IPFS CLI.
    private int getPeerCount() {
        try {
            ProcessBuilder pb = new ProcessBuilder("ipfs", "pubsub", "peers", topic);
            pb.redirectErrorStream(true);
            Process p = pb.start();
            BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()));
            int count = 1; // include self
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (!line.isEmpty()) {
                    count++;
                }
            }
            return count;
        } catch (Exception e) {
            return 1;
        }
    }

    // Simple JSON extractor for either numeric or string values
    private String extract(String json, String key) {
        try {
            String pat1 = "\"" + key + "\":\"";
            int i = json.indexOf(pat1);
            if (i != -1) {
                int j = json.indexOf("\"", i + pat1.length());
                return json.substring(i + pat1.length(), j);
            }
            String pat2 = "\"" + key + "\":";
            i = json.indexOf(pat2);
            if (i != -1) {
                int k = i + pat2.length();
                int j = k;
                while (j < json.length() && (Character.isDigit(json.charAt(j)) || json.charAt(j) == '-' || json.charAt(j) == 't' || json.charAt(j) == 'r' || json.charAt(j) == 'u' || json.charAt(j) == 'f' || json.charAt(j) == 'a' || json.charAt(j) == 'l' || json.charAt(j) == 's' || json.charAt(j) == 'e')) {
                    j++;
                }
                return json.substring(k, j);
            }
        } catch (Exception ignored) {}
        return null;
    }
}