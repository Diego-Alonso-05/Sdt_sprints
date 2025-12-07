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
            File uploadsDir = new File("uploads");
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

                // ------------------------------------------------------------
                // STEP 4 — Save embedding to /state/embeddings/embedding_<CID>.txt
                // ------------------------------------------------------------
                Path embeddingsDir = Paths.get("state", "embeddings");
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
}
