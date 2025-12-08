import common.AppLog;

import java.io.*;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Connection (Peer → Leader TCP Connection)
 * -----------------------------------------
 * Handles:
 *   - receiving the uploaded file
 *   - reading the proposal metadata (proposalId, version, hash, newCid)
 *   - generating embeddings for the uploaded file
 *   - saving embeddings to /state/embeddings/embedding_<CID>.txt
 *   - notifying the Leader (TCPServer) that a proposal is ready to broadcast
 *
 * This class is created by:
 *     new Connection(socket, tcpServerInstance)
 *
 * Network:
 *   - File is sent over plain TCP from the Peer (TCPClient)
 *   - Coordination (UPDATE_VECTOR / COMMIT_VECTOR / RAFT) goes via MQTT (MessageBus)
 */
public class Connection implements Runnable {

    private final Socket socket;
    private final TCPServer server;   // Currently unused, but kept for future extensions

    public Connection(Socket socket, TCPServer server) {
        this.socket = socket;
        this.server = server;
    }

    @Override
    public void run() {
        try {
            AppLog.log("[Connection] New peer connected: " + socket.getInetAddress());

            InputStream in = socket.getInputStream();
            BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));

            // ================================================================
            // STEP 1 — Read the proposal metadata
            // ================================================================
            String proposalId = reader.readLine();
            long version      = Long.parseLong(reader.readLine());
            String vectorHash = reader.readLine();
            String newCid     = reader.readLine();

            AppLog.log("[Connection] Proposal received:");
            AppLog.log("   id=" + proposalId);
            AppLog.log("   version=" + version);
            AppLog.log("   hash=" + vectorHash);
            AppLog.log("   newCid=" + newCid);

            // Register the proposal so the Leader can track confirmations & commits
            TCPServer.registerProposal(proposalId, version, vectorHash, newCid);

            // ================================================================
            // STEP 2 — Read the uploaded file (raw bytes)
            // ================================================================
            // Determine a stable base directory for saving state.  The working
            // directory may be the root of a multi‑module project; in that case
            // use the ServerLeader module if present.  Otherwise use the current
            // working directory.
            File baseDir = detectServerRoot();

            // Ensure the uploads folder lives inside the server module (next to src)
            File uploadsDir = new File(baseDir, "uploads");
            if (!uploadsDir.exists()) uploadsDir.mkdirs();

            File outputFile = new File(uploadsDir, proposalId + ".bin");

            try (FileOutputStream fos = new FileOutputStream(outputFile)) {
                byte[] buffer = new byte[4096];
                int bytesRead;
                // NOTE: we must not use reader again here, we switch to raw bytes
                while ((bytesRead = in.read(buffer)) != -1) {
                    fos.write(buffer, 0, bytesRead);
                }
            }

            AppLog.log("[Connection] File saved for proposal " + proposalId +
                    " at " + outputFile.getAbsolutePath());

            // ================================================================
            // STEP 3 — Generate embeddings for the uploaded file
            // ================================================================
            try {
                float[] embedding = Embeddings.generate(outputFile);
                AppLog.log("[Connection] Embedding generated, length = " + embedding.length);

                // Store the embedding in the leader for later propagation to peers.
                TCPServer.storeEmbedding(proposalId, embedding);

                // ------------------------------------------------------------
                // STEP 4 — Save embedding to state/embeddings/embedding_<CID>.txt
                // ------------------------------------------------------------
                // Use the same base directory used for uploads.  Persist
                // embeddings under a state folder to separate them from
                // temporary files and to mirror prior behaviour (state/embeddings).
                Path embeddingsDir = Paths.get(baseDir.getAbsolutePath(), "state", "embeddings");
                Files.createDirectories(embeddingsDir);

                Path embFile = embeddingsDir.resolve("embedding_" + newCid + ".txt");

                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < embedding.length; i++) {
                    if (i > 0) sb.append(",");
                    sb.append(embedding[i]);
                }

                Files.writeString(embFile, sb.toString(), StandardCharsets.UTF_8);

                AppLog.log("[Connection] Embedding stored in " + embFile.toAbsolutePath());

            } catch (Exception embEx) {
                AppLog.log("[Connection] ERROR generating/saving embedding: " + embEx.getMessage());
                embEx.printStackTrace();
                // Depending on project requirements, you might want to abort here.
                // For now, we continue so the proposal still flows through.
            }

            // ================================================================
            // STEP 5 — Notify Leader that proposal is ready → publish UPDATE_VECTOR
            // ================================================================
            // This will:
            //   - compute new global vector = old GLOBAL_VECTOR + newCid
            //   - publish UPDATE_VECTOR via MQTT
            //   - wait for CONFIRM_VECTOR and then COMMIT
            TCPServer.applyProposal(proposalId);

        } catch (Exception e) {
            AppLog.log("[Connection] ERROR: " + e.getMessage());
            e.printStackTrace();
        } finally {
            closeQuietly();
            TCPServer.decClients(this);
        }
    }

    public void closeQuietly() {
        try {
            socket.close();
        } catch (Exception ignored) {}
    }

    /**
     * Attempts to locate the root directory for the server module.  When the
     * application is started from the parent directory of multiple modules,
     * {@code user.dir} will point at the project root rather than the
     * ServerLeader module.  In that case, if a subdirectory named
     * "ServerLeader" exists, it will be used as the base directory.  If
     * {@code .venv} exists in the current working directory, we assume the
     * application has been started from within the module itself and return
     * it directly.  The returned directory is used for consistent placement
     * of uploads and embeddings.
     */
    private static File detectServerRoot() {
        File cwd = new File(System.getProperty("user.dir"));
        // If .venv exists here, we are already in the module root
        if (new File(cwd, ".venv").exists()) {
            return cwd;
        }
        // Otherwise, check for a ServerLeader subdirectory
        File alt = new File(cwd, "ServerLeader");
        if (alt.exists() && alt.isDirectory()) {
            return alt;
        }
        // Fallback: use the current working directory
        return cwd;
    }
}
