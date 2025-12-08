import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;

/**
 * Client-side file manager for embeddings.
 *
 * This class mirrors the behaviour of the server-side FileManager but writes
 * embeddings into a state directory under the peer's local root. Temporary
 * embeddings are stored in state/temp_embeddings and promoted to
 * state/embeddings on commit.
 */
public class FileManager_client {

    private static File base(String sub) {
        File root = TCPClient.getClientRoot();
        File dir = new File(root, sub);
        dir.mkdirs();
        return dir;
    }

    public static void saveTempEmbedding(String cid, float[] emb) throws IOException {
        File out = new File(base("state/temp_embeddings"), "embedding_" + cid + ".txt");
        try (PrintWriter pw = new PrintWriter(new FileWriter(out))) {
            for (int i = 0; i < emb.length; i++) {
                if (i > 0) pw.print(",");
                pw.print(emb[i]);
            }
        }
    }

    public static void promoteTempEmbedding(String cid) throws IOException {
        File temp = new File(base("state/temp_embeddings"), "embedding_" + cid + ".txt");
        if (!temp.exists())
            throw new IOException("Temp embedding not found for CID " + cid);
        File finalDir = base("state/embeddings");
        File fin = new File(finalDir, "embedding_" + cid + ".txt");
        java.nio.file.Files.move(
                temp.toPath(),
                fin.toPath(),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING
        );
    }
}