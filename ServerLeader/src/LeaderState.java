// LeaderState.java
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * LeaderState
 * ----------
 * Mantiene la versión confirmada y el vector confirmado.
 * - beginProposal -> devuelve snapshot (Proposal) immutable para crear una propuesta.
 * - applyCommit -> aplica la versión confirmada (recibe vector completo, versión y hash).
 *
 * Nota: small and synchronized to avoid races.
 */
public class LeaderState {

    private long confirmedVersion = 0L;
    private List<String> confirmedVector = new ArrayList<>();
    private String confirmedHash = computeVectorHash(confirmedVector);

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

    public synchronized Proposal beginProposal(String newCid) {
        List<String> next = new ArrayList<>(confirmedVector);
        next.add(newCid);
        long nextVersion = confirmedVersion + 1;
        String nextHash = computeVectorHash(next);
        return new Proposal(nextVersion, nextHash, Collections.unmodifiableList(new ArrayList<>(next)), newCid);
    }

    public synchronized void applyCommit(List<String> newConfirmedVector, long newVersion, String newHash) {
        this.confirmedVector = new ArrayList<>(newConfirmedVector);
        this.confirmedVersion = newVersion;
        this.confirmedHash = newHash;
    }

    public synchronized long getConfirmedVersion() { return confirmedVersion; }
    public synchronized List<String> getConfirmedVectorSnapshot() { return new ArrayList<>(confirmedVector); }
    public synchronized String getConfirmedHash() { return confirmedHash; }

    // Utility hashing (duplicable en cliente)
    public static String computeVectorHash(List<String> vector) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            String joined = String.join("|", vector);
            byte[] digest = md.digest(joined.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return "hash_err_" + (vector == null ? 0 : vector.size());
        }
    }
}
