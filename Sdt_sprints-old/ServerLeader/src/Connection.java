import java.io.*;
import java.net.*;
import java.nio.file.Files;
import java.util.List;
import java.util.UUID;

/**
 * Connection
 * -----------
 * Handles communication between the Leader server and a single client (peer).
 *
 * Supported protocol (opcodes):
 *
 *  Client → Server:
 *   10: HELLO (Peer ID)            → writeByte(10); writeUTF(peerId)
 *   11: FILE_UPLOAD                → writeByte(11); writeUTF(fileName); writeLong(size); [file bytes...]
 *   31: CONFIRM_VECTOR             → writeByte(31); writeUTF(vectorHash)
 *
 *  Server → Client:
 *    1: BROADCAST                  → writeByte(1); writeUTF(message)
 *    2: CID_RESPONSE               → writeByte(2); writeUTF(cid)
 *   21: UPDATE_VECTOR (proposal)   → writeByte(21); writeLong(version); [vector]; writeUTF(newCid); [embedding]
 *   22: COMMIT_VECTOR              → writeByte(22); writeLong(version); writeUTF(vectorHash)
 *
 *  (Legacy 20: VECTOR_UPDATE kept for compatibility, not used by the new flow.)
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

    @Override
    public void run() {
        TCPServer.incClients();
        try {
            input = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
            output = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
            TCPServer.log("New handler created for " + socket.getInetAddress().getHostAddress());

            while (running) {
                int opcode;
                try {
                    opcode = input.read();
                    if (opcode == -1) break;
                } catch (IOException e) {
                    break;
                }

                switch (opcode) {
                    case 10: { // HELLO
                        peerId = input.readUTF();
                        TCPServer.log("HELLO received from peer: " + peerId);
                        break;
                    }
                    case 11: { // FILE_UPLOAD
                        handleFileUpload();
                        break;
                    }
                    case 31: { // CONFIRM_VECTOR (peer -> leader)
                        String hash = input.readUTF();
                        TCPServer.log("CONFIRM_VECTOR from " + peerId + " hash=" + hash);
                        TCPServer.handlePeerConfirmation(peerId, hash);
                        break;
                    }
                    default:
                        TCPServer.log("Unknown opcode (" + opcode + ") from peer " + peerId);
                        break;
                }
            }
        } catch (Exception e) {
            TCPServer.log("Error in connection handler: " + e.getMessage());
        } finally {
            closeQuietly();
            TCPServer.decClients(this);
            TCPServer.log("Connection closed for peer: " + peerId);
        }
    }

    /**
     * Handles a FILE_UPLOAD message from the client.
     * Flow:
     * 1) Save file, upload to IPFS, add to MFS (unchanged).
     * 2) Generate embeddings (GUI shows "working on embeddings..." from Embeddings.generate()).
     * 3) Begin proposal on Leader (pending vector + temp embedding save).
     * 4) Broadcast UPDATE_VECTOR to peers and await CONFIRM_VECTOR (async).
     * 5) Send CID back to uploader immediately (unchanged).
     */
    private void handleFileUpload() {
        try {
            String fileName = input.readUTF();
            long fileSize = input.readLong();
            TCPServer.log("Receiving from [" + peerId + "] → " + fileName + " (" + fileSize + " bytes)");

            File uploadsDir = new File("uploads");
            if (!uploadsDir.exists()) uploadsDir.mkdirs();

            File savedFile = new File(uploadsDir, UUID.randomUUID() + "_" + fileName);

            // Read and save bytes
            try (FileOutputStream fos = new FileOutputStream(savedFile)) {
                byte[] buffer = new byte[8192];
                long remaining = fileSize;
                while (remaining > 0) {
                    int read = input.read(buffer, 0, (int) Math.min(buffer.length, remaining));
                    if (read == -1) throw new EOFException("Unexpected end of stream");
                    fos.write(buffer, 0, read);
                    remaining -= read;
                }
            }
            TCPServer.log("File saved at: " + savedFile.getAbsolutePath());

            // Upload to IPFS
            String cid = uploadToIPFS(savedFile);
            TCPServer.log("File uploaded to IPFS. CID: " + cid);

            // Add to MFS (optional)
            try {
                String encoded = URLEncoder.encode(savedFile.getName(), "UTF-8");
                URL urlMfs = new URL("http://127.0.0.1:5001/api/v0/files/cp?arg=/ipfs/" + cid + "&arg=/" + encoded);
                HttpURLConnection mfsConn = (HttpURLConnection) urlMfs.openConnection();
                mfsConn.setRequestMethod("POST");
                mfsConn.getInputStream().close();
                TCPServer.log("File added to MFS (visible in IPFS Desktop): " + savedFile.getName());
            } catch (Exception e) {
                TCPServer.log("Failed to add file to MFS: " + e.getMessage());
            }

            // Generate embeddings using the Python script (shows GUI message)
            float[] embedding;
            try {
                embedding = Embeddings.generate(savedFile);
                TCPServer.log("Embeddings generated successfully (" + embedding.length + " dimensions).");
            } catch (Exception embEx) {
                TCPServer.log("Error generating embeddings: " + embEx.getMessage());
                embedding = new float[0];
            }

            // Start the proposal (pending vector & temp embedding)
            TCPServer.beginProposalFromUpload(cid, embedding);

            // Send CID response back to the uploader immediately (same behavior)
            synchronized (output) {
                output.writeByte(2); // CID_RESPONSE
                output.writeUTF(cid);
                output.flush();
            }

        } catch (Exception e) {
            TCPServer.log("Error handling file upload from [" + peerId + "]: " + e.getMessage());
        }
    }

    /** Sends a broadcast message to this specific client. */
    public void sendBroadcast(String message) throws IOException {
        synchronized (output) {
            output.writeByte(1); // BROADCAST
            output.writeUTF(message);
            output.flush();
        }
    }

    /** Server → Client: proposal phase (opcode 21). */
    public void sendUpdateVector(long version, List<String> fullVector, String newCid, float[] embedding) throws IOException {
        synchronized (output) {
            output.writeByte(21);        // UPDATE_VECTOR
            output.writeLong(version);

            // Write full CID vector
            output.writeInt(fullVector.size());
            for (String cid : fullVector) output.writeUTF(cid);

            // New CID introduced by this proposal
            output.writeUTF(newCid);

            // Embedding of the new content (for temp index on peers)
            output.writeInt(embedding.length);
            for (float value : embedding) output.writeFloat(value);

            output.flush();
        }
    }

    /** Server → Client: commit (opcode 22). */
    public void sendCommitVector(long version, String vectorHash) throws IOException {
        synchronized (output) {
            output.writeByte(22); // COMMIT_VECTOR
            output.writeLong(version);
            output.writeUTF(vectorHash);
            output.flush();
        }
    }

    // Legacy sender (kept for compatibility; not used by the new flow).
    public void sendVectorUpdate(long version, List<String> fullVector, String newCid, float[] embedding) throws IOException {
        synchronized (output) {
            output.writeByte(20);        // VECTOR_UPDATE (legacy)
            output.writeLong(version);

            output.writeInt(fullVector.size());
            for (String cid : fullVector) output.writeUTF(cid);

            output.writeUTF(newCid);

            output.writeInt(embedding.length);
            for (float value : embedding) output.writeFloat(value);

            output.flush();
        }
    }

    /** Upload a file to the local IPFS node via HTTP (unchanged). */
    private String uploadToIPFS(File file) throws IOException {
        String boundary = "----Boundary" + System.currentTimeMillis();
        URL url = new URL("http://127.0.0.1:5001/api/v0/add?pin=true");
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();

        conn.setDoOutput(true);
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);

        try (DataOutputStream out = new DataOutputStream(conn.getOutputStream())) {
            out.writeBytes("--" + boundary + "\r\n");
            out.writeBytes("Content-Disposition: form-data; name=\"file\"; filename=\"" + file.getName() + "\"\r\n");
            out.writeBytes("Content-Type: application/octet-stream\r\n\r\n");
            Files.copy(file.toPath(), out);
            out.writeBytes("\r\n--" + boundary + "--\r\n");
            out.flush();
        }

        int responseCode = conn.getResponseCode();
        if (responseCode != 200) {
            StringBuilder err = new StringBuilder();
            try (BufferedReader ebr = new BufferedReader(new InputStreamReader(conn.getErrorStream()))) {
                String line;
                while (ebr != null && (line = ebr.readLine()) != null) err.append(line);
            }
            throw new IOException("HTTP " + responseCode + " while uploading to IPFS. Details: " + err);
        }

        StringBuilder sb = new StringBuilder();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream()))) {
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
        }

        String response = sb.toString();
        int i = response.indexOf("\"Hash\":\"");
        if (i < 0) throw new IOException("Failed to extract CID from response: " + response);
        int j = response.indexOf('"', i + 8);
        return response.substring(i + 8, j);
    }

    /** Closes all I/O resources and marks this connection as inactive. */
    public void closeQuietly() {
        running = false;
        try { if (input != null) input.close(); } catch (Exception ignored) {}
        try { if (output != null) output.close(); } catch (Exception ignored) {}
        try { socket.close(); } catch (Exception ignored) {}
    }
}
