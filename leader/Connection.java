package leader;

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
 *
 *  Server → Client:
 *    1: BROADCAST                 → writeByte(1); writeUTF(message)
 *    2: CID_RESPONSE              → writeByte(2); writeUTF(cid)
 *   20: VECTOR_UPDATE             → writeByte(20); writeLong(version); vector + embeddings
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
                    opcode = input.read(); // Non-blocking read of the next opcode
                    if (opcode == -1) break;
                } catch (IOException e) {
                    break;
                }

                switch (opcode) {
                    case 10: { // HELLO message containing Peer ID
                        peerId = input.readUTF();
                        TCPServer.log("HELLO received from peer: " + peerId);
                        break;
                    }
                    case 11: { // File upload
                        handleFileUpload();
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
     * 1. Receives the file and saves it locally.
     * 2. Uploads it to IPFS and retrieves its CID.
     * 3. Adds the file to IPFS MFS (for visibility in IPFS Desktop).
     * 4. Generates embeddings for the uploaded file.
     * 5. Updates the Leader’s CID vector and version.
     * 6. Propagates the update to all connected peers.
     * 7. Sends the CID back to the uploading client.
     */
    private void handleFileUpload() {
        try {
            String fileName = input.readUTF();
            long fileSize = input.readLong();
            TCPServer.log("Receiving from [" + peerId + "] → " + fileName + " (" + fileSize + " bytes)");

            // Create uploads directory if it doesn't exist
            File uploadsDir = new File("uploads");
            if (!uploadsDir.exists()) uploadsDir.mkdirs();

            // Create a unique local file name
            File savedFile = new File(uploadsDir, UUID.randomUUID() + "_" + fileName);

            // Read and save the uploaded file bytes
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

            // Upload file to local IPFS node
            String cid = uploadToIPFS(savedFile);
            TCPServer.log("File uploaded to IPFS. CID: " + cid);

            // Add to IPFS MFS (for IPFS Desktop visibility)
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

            // Generate embeddings using the Python script
            float[] embedding;
            try {
                embedding = Embeddings.generate(savedFile);
                TCPServer.log("Embeddings generated successfully (" + embedding.length + " dimensions).");
            } catch (Exception embEx) {
                TCPServer.log("Error generating embeddings: " + embEx.getMessage());
                embedding = new float[0];
            }

            // Update Leader state (CID vector + version)
            long newVersion = TCPServer.STATE.addCidAndBumpVersion(cid);
            List<String> snapshot = TCPServer.STATE.snapshotVector();
            TCPServer.log("Vector updated → version v" + newVersion + " (size=" + snapshot.size() + ")");

            // Propagate updated vector and embeddings to all peers
            TCPServer.broadcastVectorUpdate(newVersion, snapshot, cid, embedding);

            // Send CID response back to the uploader client
            synchronized (output) {
                output.writeByte(2); // CID_RESPONSE
                output.writeUTF(cid);
                output.flush();
            }

        } catch (Exception e) {
            TCPServer.log("Error handling file upload from [" + peerId + "]: " + e.getMessage());
        }
    }

    /** Sends a broadcast message from the Leader to this specific client. */
    public void sendBroadcast(String message) throws IOException {
        synchronized (output) {
            output.writeByte(1); // BROADCAST opcode
            output.writeUTF(message);
            output.flush();
        }
    }

    /** Sends to this client a new vector version and embeddings for a new file (opcode 20). */
    public void sendVectorUpdate(long version, List<String> fullVector, String newCid, float[] embedding) throws IOException {
        synchronized (output) {
            output.writeByte(20);        // VECTOR_UPDATE
            output.writeLong(version);

            // Write full CID vector
            output.writeInt(fullVector.size());
            for (String cid : fullVector) output.writeUTF(cid);

            // Write new CID
            output.writeUTF(newCid);

            // Write embeddings
            output.writeInt(embedding.length);
            for (float value : embedding) output.writeFloat(value);

            output.flush();
        }
    }

    /** Uploads a file to the local IPFS node using multipart/form-data (like curl -F file=@...). */
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
