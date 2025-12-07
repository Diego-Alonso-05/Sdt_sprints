package common;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.function.Consumer;

public class IPFSPubSub {

    private static final String API = "http://127.0.0.1:5001/api/v0/";

    // ---------------------------------------------------------
    // PUBLICAR MENSAJE (Kubo 0.38: multipart/form-data con 'data')
    // ---------------------------------------------------------
    public static void publish(String topic, String json) throws Exception {
        String boundary = "BOUNDARY-" + System.currentTimeMillis();
        String urlStr = API + "pubsub/pub?arg=" + topic;

        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);

        OutputStream out = conn.getOutputStream();
        BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(out, StandardCharsets.UTF_8));

        // ---- PART: data file ----
        writer.write("--" + boundary + "\r\n");
        writer.write("Content-Disposition: form-data; name=\"data\"; filename=\"msg.json\"\r\n");
        writer.write("Content-Type: application/octet-stream\r\n\r\n");
        writer.flush();

        out.write(json.getBytes(StandardCharsets.UTF_8));
        out.flush();

        writer.write("\r\n--" + boundary + "--\r\n");
        writer.flush();
        writer.close();

        int code = conn.getResponseCode();
        if (code != 200) {
            throw new RuntimeException("PubSub publish failed (HTTP " + code + "): " +
                    readStream(conn.getErrorStream()));
        }
    }


    // ---------------------------------------------------------
    // SUSCRIBIRSE (stream NDJSON)
    // ---------------------------------------------------------
    public static void subscribe(String topic, Consumer<String> handler) {

        Thread t = new Thread(() -> {
            while (true) {
                HttpURLConnection conn = null;
                try {
                    String url = API + "pubsub/sub?arg=" + topic;

                    conn = (HttpURLConnection) new URL(url).openConnection();
                    conn.setRequestMethod("POST");
                    conn.setDoInput(true);
                    conn.setDoOutput(false);

                    try (BufferedReader br = new BufferedReader(
                            new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {

                        String line;
                        while ((line = br.readLine()) != null) {
                            if (line.trim().isEmpty()) continue;

                            System.out.println("[PUBSUB RAW] " + line);

                            String decoded = extractData(line);
                            handler.accept(decoded);
                        }
                    }

                } catch (Exception e) {
                    System.err.println("[PubSub] subscribe error: " + e.getMessage());
                    try { Thread.sleep(1000); } catch (InterruptedException ignored) {}
                } finally {
                    if (conn != null) conn.disconnect();
                }
            }
        }, "PubSub-Subscriber-" + topic);

        t.setDaemon(true);
        t.start();
    }

    // ---------------------------------------------------------
    // EXTRAER Y DECODIFICAR "data":"BASE64..."
    // ---------------------------------------------------------
    private static String extractData(String jsonLine) {
        try {
            int i = jsonLine.indexOf("\"data\":\"");
            if (i == -1) return jsonLine;

            int start = i + 8;
            int end = jsonLine.indexOf("\"", start);
            if (end == -1) return jsonLine;

            String b64 = jsonLine.substring(start, end);
            byte[] decoded = Base64.getDecoder().decode(b64);
            return new String(decoded, StandardCharsets.UTF_8);

        } catch (Exception e) {
            return jsonLine;
        }
    }

    // ---------------------------------------------------------
    // LEER STREAM DE ERROR
    // ---------------------------------------------------------
    private static String readStream(InputStream is) {
        if (is == null) return "";
        StringBuilder sb = new StringBuilder();
        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(is, StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null)
                sb.append(line);
        } catch (IOException ignored) {}
        return sb.toString();
    }
}
