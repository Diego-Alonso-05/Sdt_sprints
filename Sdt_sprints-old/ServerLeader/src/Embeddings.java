import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;

/**
 * Embeddings
 * ----------
 * Runs embed/embed.py inside the project's .venv
 * - Shows a GUI log line before starting
 * - Returns the embedding as float[]
 *
 * NOTE: We do not write files here. The server saves embeddings via FileManager
 *       to temp (proposal) and final (commit) paths.
 */
public class Embeddings {

    public static float[] generate(File file) throws Exception {
        // Absolute path to your Python interpreter inside .venv
        String pythonExe = ".venv\\Scripts\\python.exe";
        String scriptPath = "embed\\embed.py"; // path to embed.py

        TCPServer.log("\nWait a moment, working on embeddings...\n");

        ProcessBuilder processBuilder = new ProcessBuilder(
                pythonExe,
                scriptPath,
                file.getAbsolutePath()
        );

        // Ensure working directory is the ServerLeader root so relative paths resolve correctly
        // (Adjust only if your project root differs.)
        processBuilder.directory(new File("C:\\Users\\qubit\\Desktop\\SDt_Project\\SDt_Project\\ServerLeader"));
        processBuilder.redirectErrorStream(true);

        Process process = processBuilder.start();

        String output = readAll(process.getInputStream());
        int exitCode = process.waitFor();

        if (exitCode != 0) {
            throw new IOException("embed.py exited with code " + exitCode + " | output=" + output);
        }

        return parseJsonFloatArray(output);
    }

    private static String readAll(InputStream inputStream) throws IOException {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream))) {
            StringBuilder result = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                result.append(line);
            }
            return result.toString();
        }
    }

    private static float[] parseJsonFloatArray(String json) throws IOException {
        String content = json.trim();
        if (content.startsWith("[") && content.endsWith("]")) {
            content = content.substring(1, content.length() - 1).trim();
            if (content.isEmpty()) {
                return new float[0];
            }

            String[] parts = content.split(",");
            float[] vector = new float[parts.length];

            for (int i = 0; i < parts.length; i++) {
                vector[i] = Float.parseFloat(parts[i].trim());
            }
            return vector;
        } else {
            throw new IOException("Invalid JSON array format: " + content);
        }
    }
}
