import common.AppLog;

import java.io.IOException;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.locks.ReentrantLock;

/**
 * VectorIndexService
 * ------------------
 *
 * Maintains an in-memory vector index for the peer. When the leader
 * publishes a COMMIT_VECTOR, the peer rebuilds its index from the
 * embeddings stored on disk in state/embeddings. The index supports
 * asynchronous updates and a simple nearest-neighbour search over the
 * confirmed CIDs using dot product similarity.
 */
public class VectorIndexService {

    private static final VectorIndexService INSTANCE = new VectorIndexService();

    private final ReentrantLock indexLock = new ReentrantLock();
    private final ExecutorService worker = Executors.newSingleThreadExecutor();

    private volatile long currentVersion = 0;
    private List<float[]> index = new ArrayList<>();
    private List<String> indexCids = new ArrayList<>();
    private Path embDir;

    private VectorIndexService() {
        File root = TCPClient.getClientRoot();
        embDir = root.toPath().resolve("state/embeddings");
        try {
            Files.createDirectories(embDir);
        } catch (Exception ignored) {}
    }

    public static VectorIndexService getInstance() {
        return INSTANCE;
    }

    /**
     * Asynchronously rebuilds the vector index using the provided confirmed
     * vector. If the provided version is not greater than the current
     * version, the request is ignored. Returns true if the update was
     * accepted.
     */
    public boolean updateIndexAsync(List<String> vector, long version) {
        if (version <= currentVersion) return false;
        worker.submit(() -> {
            try {
                updateIndexSync(vector, version);
            } catch (Exception e) {
                AppLog.log("Index update failed: " + e.getMessage());
            }
        });
        return true;
    }

    /**
     * Synchronously rebuilds the index. Loads each embedding from disk
     * based on its CID, constructs new index arrays and updates internal
     * state. Called internally by the async updater.
     */
    public boolean updateIndexSync(List<String> vector, long version) throws Exception {
        if (version <= currentVersion) return false;
        List<float[]> newIdx = new ArrayList<>();
        List<String> newCids = new ArrayList<>();
        for (String cid : vector) {
            Path p = embDir.resolve("embedding_" + cid + ".txt");
            float[] emb = loadEmbedding(p);
            newIdx.add(emb);
            newCids.add(cid);
        }
        indexLock.lock();
        try {
            currentVersion = version;
            index = newIdx;
            indexCids = newCids;
            AppLog.log("VectorIndex updated to v=" + version + " size=" + index.size());
        } finally {
            indexLock.unlock();
        }
        return true;
    }

    private float[] loadEmbedding(Path p) throws IOException {
        String content = Files.readString(p).trim();
        if (content.isEmpty()) return new float[0];
        String[] parts = content.split(",");
        float[] emb = new float[parts.length];
        for (int i = 0; i < parts.length; i++) {
            emb[i] = Float.parseFloat(parts[i].trim());
        }
        return emb;
    }

    /**
     * Performs a nearest-neighbour search using dot product similarity.
     * Returns up to topK CIDs with the highest similarity.
     */
    public List<String> search(float[] queryEmbedding, int topK) {
        if (queryEmbedding == null || topK <= 0) {
            return Collections.emptyList();
        }
        List<float[]> localIndex;
        List<String> localCids;
        indexLock.lock();
        try {
            localIndex = new ArrayList<>(this.index);
            localCids = new ArrayList<>(this.indexCids);
        } finally {
            indexLock.unlock();
        }
        if (localIndex.isEmpty()) return Collections.emptyList();
        int n = localIndex.size();
        List<int[]> sorted = new ArrayList<>(n);
        float[] q = queryEmbedding;
        for (int i = 0; i < n; i++) {
            float[] emb = localIndex.get(i);
            float score = 0f;
            int dim = Math.min(q.length, emb.length);
            for (int j = 0; j < dim; j++) {
                score += q[j] * emb[j];
            }
            // store negative score bits for sorting descending by score
            sorted.add(new int[]{i, Float.floatToRawIntBits(-score)});
        }
        Collections.sort(sorted, (a, b) -> Integer.compare(a[1], b[1]));
        int limit = Math.min(topK, sorted.size());
        List<String> results = new ArrayList<>(limit);
        for (int i = 0; i < limit; i++) {
            int idx = sorted.get(i)[0];
            results.add(localCids.get(idx));
        }
        return results;
    }
}