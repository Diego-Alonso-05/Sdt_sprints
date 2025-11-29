package leader;

import java.io.BufferedReader;
import java.io.InputStreamReader;

public class IPFSManager {

    private static final String IPFS_BIN =
            "C:\\Users\\HP1\\Desktop\\kubo\\ipfs.exe"; // cambiar si no está ahí

    public static String addToIPFS(String path) throws Exception {

        ProcessBuilder pb = new ProcessBuilder(
                IPFS_BIN, "add", "-Q", path
        );

        pb.redirectErrorStream(true);
        Process p = pb.start();

        BufferedReader br = new BufferedReader(
                new InputStreamReader(p.getInputStream())
        );

        String cid = br.readLine();
        p.waitFor();
        return cid;
    }
}
