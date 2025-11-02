package leader;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * LeaderState
 * ------------
 * This class represents the state of the Leader node.
 * It maintains:
 *  - A version number for the global document vector.
 *  - The list of all document CIDs (Content Identifiers).
 *
 * The state is synchronized to ensure thread safety since
 * multiple client connections can modify it concurrently.
 */
public class LeaderState {

    // Current version number of the document vector
    private long version = 0L;

    // List of all document CIDs managed by the Leader
    private final List<String> cidVector = new ArrayList<>();

    /**
     * Adds a new CID to the vector and increments the version number.
     *
     * @param cid The CID of the newly added document.
     * @return The new version number after the update.
     */
    public synchronized long addCidAndBumpVersion(String cid) {
        cidVector.add(cid);
        version += 1;
        return version;
    }

    /**
     * Returns a snapshot (a copy) of the current CID vector.
     * The copy prevents external modifications to the internal list.
     *
     * @return A copy of the current list of CIDs.
     */
    public synchronized List<String> snapshotVector() {
        return new ArrayList<>(cidVector);
    }

    /**
     * Returns the current version number of the document vector.
     *
     * @return The current version number.
     */
    public synchronized long currentVersion() {
        return version;
    }
}
