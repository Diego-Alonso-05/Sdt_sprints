// --- Sprint 5 ------------------------------------------------------------
// VectorIndexService.java
//
// Introduced in Sprint 5.
// Purpose:
//   Peers must maintain an in-memory vector index (FAISS-like) that matches
//   the confirmed global vector. Whenever the Leader publishes a COMMIT,
//   the peer rebuilds its index using all embeddings stored on disk.
//
//   This service handles:
//     • Loading embeddings from /state/embeddings
//     • Version-controlled index updates
//     • Asynchronous (non-blocking) updates
//     • Thread-safety with locking (PubSub vs TCP fallback)
// --------------------------------------------------------------------------

import common.AppLog;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.locks.ReentrantLock;

public class VectorIndexService {

    // --- Sprint 5 ---
    // Every peer only needs ONE index manager.
    private static final VectorIndexService INSTANCE = new VectorIndexService();

    // --- Sprint 5 ---
    // Lock that protects index rebuild from race conditions.
    // Without this, PubSub and TCP fallback commits could conflict.
    private final ReentrantLock indexLock = new ReentrantLock();

    // --- Sprint 5 ---
    // Version of the currently applied global vector.
    // Ensures only updates with higher version numbers are accepted.
    private volatile long currentVersion = 0L;

    // --- Sprint 5 ---
    // The memory index containing all loaded embeddings.
    // index[i] corresponds to confirmedVector[i].
    private List<float[]> index = new ArrayList<>();

    // --- Sprint 5 ---
    // Parallel list storing the CID of each embedding (mainly for debugging).
    private List<String> indexCids = new ArrayList<>();

    // --- Sprint 5 ---
    // True while an async index rebuild is running.
    private volatile boolean updating = false;

    // --- Sprint 5 ---
    // Worker thread where index rebuilds happen.
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "vector-index-worker");
        t.setDaemon(true);
        return t;
    });

    // --- Sprint 5 ---
    // Folder where embeddings files are stored.
    private final Path embeddingsDir = Paths.get("state", "embeddings");

    // --- Sprint 5 ---
    // Private constructor → enforced singleton + folder creation.
    private VectorIndexService() {
        try {
            Files.createDirectories(embeddingsDir);
        } catch (Exception ignored) {}
    }

    // --- Sprint 5 ---
    public static VectorIndexService getInstance() {
        return INSTANCE;
    }

    // --- Sprint 5 ---
    public long getCurrentVersion() {
        return currentVersion;
    }

    // --- Sprint 5 ---
    public int getIndexSize() {
        return index.size();
    }

    // --- Sprint 5 ---
    // Returns a copy of the CIDs for UI/inspection/debug.
    public List<String> getIndexCids() {
        return new ArrayList<>(indexCids);
    }

    // --- Sprint 5 ---
    // Asynchronously rebuilds the vector index.
    // Non-blocking: does NOT freeze UI or PubSub listener.
    public boolean updateIndexAsync(List<String> confirmedVector, long version) {

        // Ignore outdated commits
        if (version <= currentVersion) {
            return false;
        }

        updating = true;

        worker.submit(() -> {
            try {
                updateIndexSync(confirmedVector, version);
            } catch (Exception e) {
                AppLog.log("VectorIndexService update failed: " + e.getMessage());
            } finally {
                updating = false;
            }
        });

        return true;
    }

    // --- Sprint 5 ---
    // Synchronous index rebuild (used internally).
    // Loads embeddings from disk, rebuilds memory structures,
    // and applies update ONLY if the version increases.
    public boolean updateIndexSync(List<String> confirmedVector, long version) throws Exception {

        if (version <= currentVersion) {
            return false; // outdated commit
        }

        List<float[]> newIndex = new ArrayList<>(confirmedVector.size());
        List<String> newCids = new ArrayList<>(confirmedVector.size());

        // For each CID, load the embedding file
        for (String cid : confirmedVector) {
            Path file = embeddingsDir.resolve("embedding_" + cid + ".txt");

            float[] emb = null;
            boolean loaded = false;
            int attempts = 0;

            // Retry 2 times in case commit + file copy races
            while (!loaded && attempts < 2) {
                attempts++;

                if (Files.exists(file)) {
                    try {
                        emb = readEmbeddingFromFile(file);
                        loaded = true;
                    } catch (IOException ioe) {
                        AppLog.log("Failed reading embedding for CID " + cid +
                                " attempt " + attempts + ": " + ioe.getMessage());
                        Thread.sleep(100);  // short retry delay
                    }
                } else {
                    Thread.sleep(100);
                }
            }

            if (!loaded || emb == null) {
                throw new IOException("Embedding missing for CID: " + cid);
            }

            newIndex.add(emb);
            newCids.add(cid);
        }

        // Prevent concurrent update collisions
        indexLock.lock();
        try {

            // Version double-check inside lock
            if (version <= currentVersion) {
                return false;
            }

            this.index = newIndex;
            this.indexCids = newCids;
            this.currentVersion = version;

            AppLog.log("VectorIndexService: index updated to version " + version +
                    " (size=" + index.size() + ")");

            return true;

        } finally {
            indexLock.unlock();
        }
    }

    // --- Sprint 5 ---
    // Reads embedding vector from a text file containing comma-separated floats.
    private float[] readEmbeddingFromFile(Path file) throws IOException {

        String content = new String(
                Files.readAllBytes(file),
                java.nio.charset.StandardCharsets.UTF_8
        ).trim();

        if (content.isEmpty()) return new float[0];

        String[] parts = content.split(",");
        List<Float> tmp = new ArrayList<>(parts.length);

        for (String p : parts) {
            p = p.trim();
            if (!p.isEmpty()) tmp.add(Float.parseFloat(p));
        }

        float[] emb = new float[tmp.size()];
        for (int i = 0; i < tmp.size(); i++) emb[i] = tmp.get(i);

        return emb;
    }

    // --- Sprint 5 ---
    // Returns embedding by its CID (or null if not present).
    // Thread-safe read guarded by lock.
    public float[] getEmbeddingByCid(String cid) {
        indexLock.lock();
        try {
            for (int i = 0; i < indexCids.size(); i++) {
                if (indexCids.get(i).equals(cid)) return index.get(i);
            }
            return null;
        } finally {
            indexLock.unlock();
        }
    }

    // --- Sprint 5 ---
    // Gracefully shuts down async worker.
    public void shutdown() {
        worker.shutdown();
        try {
            if (!worker.awaitTermination(1, TimeUnit.SECONDS)) {
                worker.shutdownNow();
            }
        } catch (InterruptedException ignored) {
            worker.shutdownNow();
        }
    }
}
