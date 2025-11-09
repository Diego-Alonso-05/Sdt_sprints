package leader;

import java.io.*;

/**
 * Embeddings
 * -----------
 * This class is responsible for generating embedding vectors
 * for uploaded documents using a Python script (`embed/embed.py`).
 *
 * The Python script produces a JSON array of floating-point values
 * that represent the semantic embedding of a file's contents.
 */
public class Embeddings {

    /**
     * Generates embeddings for a given file.
     *
     * The method tries to execute the Python script inside a virtual environment first
     * (`venv\Scripts\python.exe`). If that fails, it falls back to the default `python` command.
     *
     * @param file The file to generate embeddings for.
     * @return A float array containing the embedding vector.
     * @throws Exception If the script fails or produces invalid output.
     */
    public static float[] generate(File file) throws Exception {
        String pythonPath = "venv\\Scripts\\python.exe";
        ProcessBuilder processBuilder = new ProcessBuilder(pythonPath, "embed\\embed.py", file.getAbsolutePath());
        processBuilder.redirectErrorStream(true);

        Process process;
        try {
            // Try using the Python from the virtual environment
            process = processBuilder.start();
        } catch (IOException e) {
            // If that fails, fall back to the system-wide Python installation
            processBuilder = new ProcessBuilder("python", "embed\\embed.py", file.getAbsolutePath());
            processBuilder.redirectErrorStream(true);
            process = processBuilder.start();
        }

        // Capture the Python script output
        String output = readAll(process.getInputStream());
        int exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new IOException("embed.py exited with code " + exitCode + " | output=" + output);
        }

        // The script should return a JSON array: [x, y, z, ...]
        return parseJsonFloatArray(output);
    }

    /**
     * Reads all text from an InputStream and returns it as a String.
     *
     * @param inputStream The input stream to read.
     * @return The complete text read from the stream.
     * @throws IOException If an I/O error occurs.
     */
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

    /**
     * Parses a JSON array string (e.g., "[0.1, -0.2, 0.3]") into a float array.
     *
     * @param json The JSON array string.
     * @return A float array parsed from the JSON input.
     * @throws IOException If the JSON format is invalid.
     */
    private static float[] parseJsonFloatArray(String json) throws IOException {
        String content = json.trim();
        if (!content.startsWith("[") || !content.endsWith("]")) {
            throw new IOException("Invalid JSON array format: " + content);
        }

        // Remove brackets and trim spaces
        content = content.substring(1, content.length() - 1).trim();
        if (content.isEmpty()) {
            return new float[0];
        }

        // Split by commas and convert each element to a float
        String[] parts = content.split(",");
        float[] vector = new float[parts.length];
        for (int i = 0; i < parts.length; i++) {
            vector[i] = Float.parseFloat(parts[i].trim());
        }
        return vector;
    }
}
