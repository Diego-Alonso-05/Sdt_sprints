package common;

import java.io.*;
import java.util.Map;
import java.util.function.Consumer;

public class IPFSPubSub {

    // 🔥 BINARIO REAL QUE USA TU IPFS DESKTOP
    private static final String IPFS_BIN =
        "C:\\Users\\HP1\\AppData\\Local\\Programs\\IPFS Desktop\\resources\\app.asar.unpacked\\node_modules\\kubo\\kubo\\ipfs.exe";

    // 🔥 REPO REAL DE TU NODO
    private static final String IPFS_REPO =
        "C:\\Users\\HP1\\.ipfs";

    private static ProcessBuilder prepare(String... cmd) {
        ProcessBuilder pb = new ProcessBuilder(cmd);

        // MUY IMPORTANTE: usar el repo correcto
        Map<String, String> env = pb.environment();
        env.put("IPFS_PATH", IPFS_REPO);

        pb.redirectErrorStream(true);
        return pb;
    }

    // ========================
    //      PUBLICAR MENSAJE
    // ========================
    public static void publish(String topic, String message) throws Exception {

    // Comando EXACTO que funciona en tu PowerShell
    String psCmd =
        "'" + message + "' | & '" + IPFS_BIN + "' pubsub pub " + topic;

    ProcessBuilder pb = new ProcessBuilder(
            "powershell.exe", "-Command", psCmd
    );

    // USA TU REPO REAL
    pb.environment().put("IPFS_PATH", IPFS_REPO);

    pb.redirectErrorStream(true);
    Process p = pb.start();

    BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()));
    String line;
    while ((line = br.readLine()) != null) {
        System.out.println("[PUB] " + line);
    }

    p.waitFor();
}



    // ========================
    //      SUBSCRIBIRSE
    // ========================
    public static void subscribe(String topic, Consumer<String> callback) {

        new Thread(() -> {
            try {
                String cmd = "\"" + IPFS_BIN + "\" pubsub sub " + topic;

                ProcessBuilder pb = new ProcessBuilder("cmd.exe", "/c", cmd);
                pb.environment().put("IPFS_PATH", IPFS_REPO);
                pb.redirectErrorStream(true);

                Process p = pb.start();

                BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()));
                String line;

                while ((line = br.readLine()) != null) {
                    callback.accept(line);
                }

            } catch (Exception e) {
                System.err.println("❌ Error en subscribe: " + e.getMessage());
            }
        }).start();
    }
}
