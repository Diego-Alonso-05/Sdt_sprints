package cliente;

import java.io.*;
import java.net.Socket;

public class TCPClient {

    private final String host;
    private final int port;

    public TCPClient(String host, int port) {
        this.host = host;
        this.port = port;
    }

    public String sendFile(File file) {
        try (Socket socket = new Socket(host, port);
             DataOutputStream out = new DataOutputStream(socket.getOutputStream());
             FileInputStream fis = new FileInputStream(file)) {

            out.writeUTF(file.getName());
            out.writeLong(file.length());

            byte[] buffer = new byte[4096];
            int bytes;
            while ((bytes = fis.read(buffer)) != -1) {
                out.write(buffer, 0, bytes);
            }

            out.flush();
            return "Archivo enviado correctamente al líder.";

        } catch (IOException e) {
            e.printStackTrace();
            return "Error enviando archivo: " + e.getMessage();
        }
    }
}
