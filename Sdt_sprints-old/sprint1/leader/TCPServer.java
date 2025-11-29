package leader;

import common.IPFSPubSub;

import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;

public class TCPServer extends Thread {

    private final int port;
    private final LeaderGUI gui;
    private final String topic;
    private boolean running = true;

    public TCPServer(int port, LeaderGUI gui, String topic) {
        this.port = port;
        this.gui = gui;
        this.topic = topic;
    }

    @Override
    public void run() {
        try (ServerSocket server = new ServerSocket(port)) {

            gui.log("🟢 Servidor TCP listo en puerto " + port);

            while (running) {
                Socket socket = server.accept();
                new Thread(() -> handle(socket)).start();
            }

        } catch (IOException e) {
            gui.log("❌ Error en TCPServer: " + e.getMessage());
        }
    }

    private void handle(Socket socket) {
    try (DataInputStream in = new DataInputStream(socket.getInputStream())) {

        int attempts = 0;
        while (in.available() == 0 && attempts < 20) {
            Thread.sleep(10);
            attempts++;
        }

        // Si sigue sin datos → es un testSocket
        if (in.available() == 0) {
            socket.close();
            return;
        }


        String fileName = in.readUTF();
        long fileSize = in.readLong();

        File uploads = new File("uploads");
        if (!uploads.exists()) uploads.mkdir();

        File file = new File(uploads, fileName);

        try (FileOutputStream fos = new FileOutputStream(file)) {
            byte[] buffer = new byte[4096];
            long readTotal = 0;

            while (readTotal < fileSize) {
                int read = in.read(buffer);
                if (read == -1) break;

                fos.write(buffer, 0, read);
                readTotal += read;
            }
        }

        gui.log("📥 Archivo recibido: " + fileName + " (" + fileSize + " bytes)");

        // Subir a IPFS
        String cid = IPFSManager.addToIPFS(file.getAbsolutePath());
        gui.log("🧩 Subido a IPFS → CID: " + cid);

        // Notificar a peers
        String payload = "ARCHIVO|" + fileName + "|" + cid;
        IPFSPubSub.publish(topic, payload);
        gui.log("📡 Notificado a peers → " + payload);

    } catch (Exception e) {
        // lo dejas vacío si quieres
        gui.log("Error al conectar el cliente");
    }
}

}
