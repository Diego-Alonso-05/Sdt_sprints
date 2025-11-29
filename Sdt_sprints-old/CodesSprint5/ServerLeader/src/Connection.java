import java.io.*;
import java.net.Socket;
import java.net.URLEncoder;
import java.nio.file.Files;
import java.util.List;
import java.util.UUID;
import java.net.URL;
import java.net.HttpURLConnection;

import common.AppLog;   // <<—— NEW: global logger

/**
 * Connection
 *
 * This class represents a dedicated handler thread for each peer connected
 * to the Leader (TCPServer). It manages:
 *
 * - Receiving opcodes sent from the peer (HELLO, FILE_UPLOAD, CONFIRM_VECTOR).
 * - Handling file uploads over TCP.
 * - Uploading files to IPFS and generating embeddings.
 * - Triggering proposal creation on the Leader with beginProposalFromUpload().
 * - Receiving CONFIRM_VECTOR via TCP fallback and forwarding to the Leader.
 *
 * Improvements matching the professor’s feedback:
 * - CONFIRM_VECTOR now includes proposalId for multi-proposal safety. // new
 * - TCP confirm is fallback only; main confirm path is PubSub on client side. // new
 * - beginProposalFromUpload uses the new concurrent proposal handling in the Leader. // new
 */
public class Connection extends Thread {

    private final Socket socket;
    private DataInputStream input;
    private DataOutputStream output;
    private volatile boolean running = true;
    private String peerId = "Unknown";

    public Connection(Socket socket) {
        this.socket = socket;
        setName("conn-" + socket.getInetAddress().getHostAddress());
    }

    public String getPeerAddress() {
        return socket.getInetAddress().getHostAddress();
    }

    @Override
    public void run() {
        TCPServer.incClients();
        try {
            input = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
            output = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));

            AppLog.log("New handler created for " + getPeerAddress());

            while (running) {
                int opcode;

                try {
                    opcode = input.read();
                    if (opcode == -1) break;
                } catch (IOException e) {
                    break;
                }

                switch (opcode) {

                    case 10: { // HELLO opcode
                        peerId = input.readUTF();
                        AppLog.log("HELLO from peer: " + peerId);
                        break;
                    }

                    case 11: { // FILE_UPLOAD opcode
                        handleFileUpload();
                        break;
                    }

                    case 31: {
                        // CONFIRM_VECTOR via TCP fallback
                        // Includes proposalId + hash so the Leader can map it correctly. // new
                        String proposalId = input.readUTF();
                        String hash = input.readUTF();
                        AppLog.log(
                                "CONFIRM_VECTOR (TCP fallback) received from " + peerId +
                                        " proposal=" + proposalId +
                                        " hash=" + hash
                        );

                        // Delegate to Leader’s concurrent proposal handler // new
                        TCPServer.handlePeerConfirmation(proposalId, peerId, hash);
                        break;
                    }

                    default:
                        AppLog.log("Unknown opcode (" + opcode + ") from peer " + peerId);
                        break;
                }
            }

        } catch (Exception e) {
            AppLog.log("Error in connection handler: " + e.getMessage());

        } finally {
            closeQuietly();
            TCPServer.decClients(this);
            AppLog.log("Connection closed for peer: " + peerId);
        }
    }

    /**
     * Handles FILE_UPLOAD sent from the peer via TCP.
     *
     * Steps:
     * - Save file locally.
     * - Upload file to IPFS to obtain CID.
     * - Generate vector embeddings.
     * - Trigger proposal creation on the Leader using beginProposalFromUpload().
     * - Send CID back to peer as response.
     */
    private void handleFileUpload() {
        try {
            String fileName = input.readUTF();
            long fileSize = input.readLong();
            AppLog.log(
                    "Receiving upload from [" + peerId + "] → " +
                            fileName + " (" + fileSize + " bytes)"
            );

            File uploadsDir = new File("uploads");
            if (!uploadsDir.exists()) uploadsDir.mkdirs();

            File savedFile = new File(uploadsDir, UUID.randomUUID() + "_" + fileName);

            try (FileOutputStream fos = new FileOutputStream(savedFile)) {
                byte[] buffer = new byte[8192];
                long remaining = fileSize;

                while (remaining > 0) {
                    int read = input.read(buffer, 0, (int) Math.min(buffer.length, remaining));
                    if (read == -1) throw new EOFException("Unexpected EOF");
                    fos.write(buffer, 0, read);
                    remaining -= read;
                }
            }

            AppLog.log("File saved at: " + savedFile.getAbsolutePath());

            // Upload file to IPFS
            String cid = uploadToIPFS(savedFile);
            AppLog.log("File uploaded to IPFS. CID: " + cid);

            // Add to MFS (best-effort)
            try {
                String encoded = URLEncoder.encode(savedFile.getName(), "UTF-8");
                URL url = new URL(
                        "http://127.0.0.1:5001/api/v0/files/cp?arg=/ipfs/" + cid + "&arg=/" + encoded
                );
                HttpURLConnection mfsConn = (HttpURLConnection) url.openConnection();
                mfsConn.setRequestMethod("POST");
                mfsConn.getInputStream().close();

                AppLog.log("Added to MFS: " + savedFile.getName());

            } catch (Exception e) {
                AppLog.log("Failed to add to MFS: " + e.getMessage());
            }

            // Generate embeddings
            float[] embedding;
            try {
                embedding = Embeddings.generate(savedFile);
                AppLog.log("Embeddings generated (" + embedding.length + " dims)");
            } catch (Exception embEx) {
                AppLog.log("Embeddings error: " + embEx.getMessage());
                embedding = new float[0];
            }

            // Trigger proposal creation
            TCPServer.beginProposalFromUpload(cid, embedding);

            // Send the CID back to the peer
            synchronized (output) {
                output.writeByte(2);
                output.writeUTF(cid);
                output.flush();
            }

        } catch (Exception e) {
            AppLog.log("Upload error from " + peerId + ": " + e.getMessage());
        }
    }

    /**
     * Sends a broadcast message to the peer (used by Leader).
     */
    public void sendBroadcast(String message) throws IOException {
        synchronized (output) {
            output.writeByte(1);
            output.writeUTF(message);
            output.flush();
        }
    }

    /**
     * Sends UPDATE_VECTOR through TCP fallback.
     * This includes full vector and embedding.
     * Used only when PubSub is not available on the client. // new
     */
    public void sendUpdateVectorWithId(
            String proposalId,
            long version,
            List<String> fullVector,
            String newCid,
            float[] embedding
    ) throws IOException {

        synchronized (output) {
            output.writeByte(21); // UPDATE_VECTOR opcode (new format)
            output.writeUTF(proposalId); // new
            output.writeLong(version);

            output.writeInt(fullVector.size());
            for (String cid : fullVector) output.writeUTF(cid);

            output.writeUTF(newCid);

            output.writeInt(embedding.length);
            for (float v : embedding) output.writeFloat(v);

            output.flush();
        }
    }

    /**
     * Sends COMMIT_VECTOR via TCP fallback.
     */
    public void sendCommitVector(long version, String vectorHash) throws IOException {
        synchronized (output) {
            output.writeByte(22);
            output.writeLong(version);
            output.writeUTF(vectorHash);
            output.flush();
        }
    }

    /**
     * Uploads a file to IPFS via HTTP multipart request.
     * Returns the CID extracted from the JSON response.
     */
    private String uploadToIPFS(File file) throws IOException {

        String boundary = "----Boundary" + System.currentTimeMillis();
        URL url = new URL("http://127.0.0.1:5001/api/v0/add?pin=true");

        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setDoOutput(true);
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);

        try (DataOutputStream out = new DataOutputStream(conn.getOutputStream())) {

            out.writeBytes("--" + boundary + "\r\n");
            out.writeBytes(
                    "Content-Disposition: form-data; name=\"file\"; filename=\"" +
                            file.getName() + "\"\r\n"
            );
            out.writeBytes("Content-Type: application/octet-stream\r\n\r\n");

            Files.copy(file.toPath(), out);

            out.writeBytes("\r\n--" + boundary + "--\r\n");
            out.flush();
        }

        int responseCode = conn.getResponseCode();
        if (responseCode != 200) {
            StringBuilder err = new StringBuilder();

            try (BufferedReader ebr =
                         new BufferedReader(new InputStreamReader(conn.getErrorStream()))) {

                String line;
                while (ebr != null && (line = ebr.readLine()) != null)
                    err.append(line);

            } catch (Exception ignored) {}

            throw new IOException("HTTP " + responseCode + ": " + err);
        }

        // Read JSON reply
        StringBuilder sb = new StringBuilder();
        try (BufferedReader br =
                     new BufferedReader(new InputStreamReader(conn.getInputStream()))) {

            String line;
            while ((line = br.readLine()) != null)
                sb.append(line);
        }

        String response = sb.toString();
        int i = response.indexOf("\"Hash\":\"");
        if (i < 0) throw new IOException("CID missing: " + response);

        int j = response.indexOf('"', i + 8);
        return response.substring(i + 8, j);
    }

    /**
     * Quietly closes socket and streams.
     */
    public void closeQuietly() {
        running = false;

        try { if (input != null) input.close(); } catch (Exception ignored) {}
        try { if (output != null) output.close(); } catch (Exception ignored) {}
        try { socket.close(); } catch (Exception ignored) {}
    }
}
