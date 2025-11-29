package leader;

import java.io.*;
import java.nio.file.*;

/**
 * FileManager
 * ------------
 * Handles basic file management tasks for the Leader.
 * Responsibilities include:
 *  - Saving uploaded files to the local "uploads" directory.
 *  - Adding files to the IPFS network via command-line integration.
 */
public class FileManager {

    /**
     * Saves a file to the "uploads" directory on disk.
     *
     * @param fileName The name of the file to save.
     * @param data     The binary contents of the file.
     * @return The full path of the saved file.
     * @throws IOException If there is a problem writing the file.
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
     * Adds a file to the local IPFS node using the "ipfs add" command.
     *
     * This method executes the IPFS CLI command as a subprocess, reads the output,
     * and extracts the resulting CID (Content Identifier).
     *
     * @param filePath The path to the file to be added to IPFS.
     * @return The CID string if successful, or an error message otherwise.
     */
    public static String addToIPFS(String filePath) {
        try {
            // Build and start the IPFS process
            ProcessBuilder processBuilder = new ProcessBuilder("ipfs", "add", filePath);
            processBuilder.redirectErrorStream(true);
            Process process = processBuilder.start();

            // Read command output
            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            String line;
            String cid = null;

            while ((line = reader.readLine()) != null) {
                if (line.startsWith("added")) {
                    // Example output: "added Qm123abc file.txt"
                    String[] parts = line.split(" ");
                    if (parts.length > 1) {
                        cid = parts[1];
                    }
                }
            }

            process.waitFor();
            return cid != null ? cid : "Failed to add file to IPFS";

        } catch (Exception e) {
            e.printStackTrace();
            return "IPFS error: " + e.getMessage();
        }
    }
}
