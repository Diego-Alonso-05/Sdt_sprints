import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * LeaderState
 * -----------
 * Keeps confirmed and pending vectors for the leader, plus versions and hashes.
 * Only one pending proposal is allowed at a time.
 */
public class LeaderState {

    /** Confirmed state (visible to the system). */
    private long confirmedVersion = 0L;
    private List<String> confirmedVector = new ArrayList<>();
    private String confirmedHash = computeVectorHash(confirmedVector);

    /** Pending state (active proposal). Null when there's no in-flight proposal. */
    private long pendingVersion = 0L;
    private List<String> pendingVector = null;
    private String pendingHash = null;
    private String pendingNewCid = null;

    /** Immutable snapshot used by the server when beginning a proposal. */
    public static final class Proposal {
        public final long version;
        public final String vectorHash;
        public final List<String> fullVector;
        public final String newCid;

        Proposal(long version, String vectorHash, List<String> fullVector, String newCid) {
            this.version = version;
            this.vectorHash = vectorHash;
            this.fullVector = fullVector;
            this.newCid = newCid;
        }
    }

    /** Begin a new proposal by appending the new CID to the confirmed vector. */
    public synchronized Proposal beginProposal(String newCid) {
        if (pendingVector != null) {
            throw new IllegalStateException("A proposal is already in-flight.");
        }
        List<String> next = new ArrayList<>(confirmedVector);
        next.add(newCid);

        pendingVector = next;
        pendingVersion = confirmedVersion + 1;
        pendingHash = computeVectorHash(pendingVector);
        pendingNewCid = newCid;

        return new Proposal(pendingVersion, pendingHash, Collections.unmodifiableList(new ArrayList<>(pendingVector)), newCid);
    }

    /** Promote pending -> confirmed. No-op if nothing pending. */
    public synchronized void applyCommit() {
        if (pendingVector == null) return;
        confirmedVector = pendingVector;
        confirmedVersion = pendingVersion;
        confirmedHash = pendingHash;

        // clear pending
        pendingVector = null;
        pendingHash = null;
        pendingVersion = 0L;
        pendingNewCid = null;
    }

    /** Discard pending proposal (e.g., if conflicts/abort). */
    public synchronized void discardPending() {
        pendingVector = null;
        pendingHash = null;
        pendingVersion = 0L;
        pendingNewCid = null;
    }

    // ----- Read helpers -----

    public synchronized long getConfirmedVersion() { return confirmedVersion; }
    public synchronized List<String> getConfirmedVectorSnapshot() { return new ArrayList<>(confirmedVector); }
    public synchronized String getConfirmedHash() { return confirmedHash; }

    public synchronized Long getPendingVersionOrNull() { return (pendingVector == null) ? null : pendingVersion; }
    public synchronized String getPendingHashOrNull() { return pendingHash; }
    public synchronized List<String> getPendingVectorSnapshotOrNull() {
        return (pendingVector == null) ? null : new ArrayList<>(pendingVector);
    }
    public synchronized String getPendingNewCidOrNull() { return pendingNewCid; }

    // ----- Utility -----

    /** Deterministic hash of the vector contents (SHA-256 over joined CIDs). */
    public static String computeVectorHash(List<String> vector) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            String joined = String.join("|", vector);
            byte[] digest = md.digest(joined.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            // Extremely unlikely; fall back to size-based string
            return "hash_err_" + vector.size();
        }
    }
}
