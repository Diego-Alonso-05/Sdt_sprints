
import common.AppLog;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;

public class Embeddings {

    public static float[] generate(File file) throws Exception {

        // 🔥 Rutas ABSOLUTAS del entorno de Diego (modificables)
        String pythonExe =
                "C:\\Users\\di17j\\OneDrive\\Escritorio\\Sdt_sprints\\ServerLeader\\.venv\\Scripts\\python.exe";

        String scriptPath =
                "C:\\Users\\di17j\\OneDrive\\Escritorio\\Sdt_sprints\\ServerLeader\\embed\\embed.py";

        // 🔥 Log correcto usando el logger global
        AppLog.log("Using Python: " + pythonExe);
        AppLog.log("Using embed script: " + scriptPath);
        AppLog.log("\nWait a moment, working on embeddings...\n");

        ProcessBuilder processBuilder = new ProcessBuilder(
                pythonExe,
                scriptPath,
                file.getAbsolutePath()
        );

        // 🔥 Directorio de trabajo real del Leader
        processBuilder.directory(
                new File("C:\\Users\\di17j\\OneDrive\\Escritorio\\Sdt_sprints\\ServerLeader")
        );

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
        try (BufferedReader reader =
                     new BufferedReader(new InputStreamReader(inputStream))) {

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
