import java.io.*;
import java.nio.file.*;

/**
 * FileManager
 * ------------
 * Leader-side file helpers:
 *  - Save uploaded files (unchanged)
 *  - Save embeddings TEMPORARILY during proposal
 *  - Promote embeddings to FINAL on commit
 */
public class FileManager {

    /**
     * Saves a file to the "uploads" directory on disk.
     */
    public static String saveFile(String fileName, byte[] data) throws IOException {
        File uploadsDir = new File("uploads");
        if (!uploadsDir.exists()) {
            uploadsDir.mkdir();
        }
        String path = "uploads/" + fileName;
        Files.write(Paths.get(path), data);
        System.out.println("File saved at: " + path);
        return path;
    }

    /**
     * Adds a file to the local IPFS node using "ipfs add".
     * (Kept here but not used directly by Connection; we left that logic inline.)
     */
    public static String addToIPFS(String filePath) {
        try {
            ProcessBuilder processBuilder = new ProcessBuilder("ipfs", "add", filePath);
            processBuilder.redirectErrorStream(true);
            Process process = processBuilder.start();

            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            String line;
            String cid = null;

            while ((line = reader.readLine()) != null) {
                if (line.startsWith("added")) {
                    String[] parts = line.split(" ");
                    if (parts.length > 1) cid = parts[1];
                }
            }

            process.waitFor();
            return cid != null ? cid : "Failed to add file to IPFS";

        } catch (Exception e) {
            e.printStackTrace();
            return "IPFS error: " + e.getMessage();
        }
    }

    // -----------------------
    // Embedding persistence
    // -----------------------

    private static File ensureDir(File dir) throws IOException {
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IOException("Failed to create directory: " + dir.getAbsolutePath());
        }
        return dir;
    }

    /** Save proposal-time embeddings to state/temp_embeddings/. */
    public static void saveTempEmbedding(String cid, float[] embedding) throws IOException {
        File dir = ensureDir(new File("state/temp_embeddings"));
        File out = new File(dir, "embedding_" + cid + ".txt");
        try (FileWriter fw = new FileWriter(out)) {
            for (float v : embedding) fw.write(v + "\n");
        }
    }

    /** Move temp embedding to state/embeddings/ on commit. */
    public static void promoteTempEmbedding(String cid) throws IOException {
        File temp = new File("state/temp_embeddings/embedding_" + cid + ".txt");
        File finalDir = ensureDir(new File("state/embeddings"));
        File fin = new File(finalDir, "embedding_" + cid + ".txt");

        if (!temp.exists()) {
            throw new FileNotFoundException("Temp embedding not found for CID " + cid + ": " + temp.getAbsolutePath());
        }
        // Overwrite if exists (idempotent commits)
        Files.move(temp.toPath(), fin.toPath(), StandardCopyOption.REPLACE_EXISTING);
    }

    /** Optional: clean a temp embedding (if proposal aborted). */
    public static void deleteTempEmbedding(String cid) {
        File temp = new File("state/temp_embeddings/embedding_" + cid + ".txt");
        if (temp.exists()) {
            // best-effort delete
            try { Files.delete(temp.toPath()); } catch (Exception ignored) {}
        }
    }
}
