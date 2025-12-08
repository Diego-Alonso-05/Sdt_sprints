import common.AppLog;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;

public class Embeddings {

    public static float[] generate(File file) throws Exception {

        // === Dynamic project root detection ===
        // Attempt to locate the module root containing .venv and embed/embed.py.  If the
        // application is started from the parent directory of multiple modules,
        // System.getProperty("user.dir") will point at the project root.  In that
        // case we fall back to a ServerLeader subdirectory.  If the expected files
        // are still not found, throw an informative error.
        File cwd = new File(System.getProperty("user.dir"));
        File base = cwd;
        File pyExe = new File(base, ".venv\\Scripts\\python.exe");
        File script = new File(base, "embed\\embed.py");

        if (!pyExe.exists() || !script.exists()) {
            // check for ServerLeader submodule
            File alt = new File(cwd, "ServerLeader");
            File altExe = new File(alt, ".venv\\Scripts\\python.exe");
            File altScript = new File(alt, "embed\\embed.py");
            if (alt.exists() && altExe.exists() && altScript.exists()) {
                base = alt;
                pyExe = altExe;
                script = altScript;
            }
        }

        if (!pyExe.exists()) {
            throw new IOException("Python executable not found. Searched: "
                    + pyExe.getAbsolutePath());
        }
        if (!script.exists()) {
            throw new IOException("embed.py not found. Searched: "
                    + script.getAbsolutePath());
        }

        AppLog.log("Using Python: " + pyExe.getAbsolutePath());
        AppLog.log("Using embed script: " + script.getAbsolutePath());
        AppLog.log("\nWait a moment, working on embeddings...\n");

        // Prepare process builder to run the embed script.  Invoke Python directly
        // rather than through cmd.exe; the working directory is explicitly set
        // so that relative imports inside embed.py work correctly.
        ProcessBuilder processBuilder = new ProcessBuilder(
                pyExe.getAbsolutePath(),
                script.getAbsolutePath(),
                file.getAbsolutePath()
        );

        // Set the working directory to the module root
        processBuilder.directory(base);

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
