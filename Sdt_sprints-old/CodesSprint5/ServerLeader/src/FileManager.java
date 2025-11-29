// FileManager.java
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;

public class FileManager {

    public static void saveTempEmbedding(String cid, float[] embedding) throws IOException {
        File dir = new File("state/temp_embeddings");
        dir.mkdirs();
        File file = new File(dir, "embedding_" + cid + ".txt");
        try (PrintWriter pw = new PrintWriter(new FileWriter(file))) {
            for (float v : embedding) pw.print(v + ",");
        }
    }

    public static void promoteTempEmbedding(String cid) throws IOException {
        File temp = new File("state/temp_embeddings/embedding_" + cid + ".txt");
        File finalDir = new File("state/embeddings");
        finalDir.mkdirs();
        File fin = new File(finalDir, "embedding_" + cid + ".txt");
        if (temp.exists()) {
            java.nio.file.Files.move(temp.toPath(), fin.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } else {
            throw new IOException("Temp embedding not found for CID " + cid);
        }
    }
}
