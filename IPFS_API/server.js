// ----- IMPORTS & BASIC SETUP -----
import express from "express";
import multer from "multer";
import { create } from "kubo-rpc-client";
import cors from "cors";
import fs from "fs";
import path from "path";

const app = express();
const upload = multer({ storage: multer.memoryStorage() });
const PORT = 3000;

app.use(cors());
app.use(express.json());

const ipfs = create({
  url: "http://127.0.0.1:5001/api/v0"
});

// ----- PEER MANAGEMENT -----
const discoveredPeers = new Map();

async function connectToPeer(peerAddrs) {
  for (const addr of peerAddrs) {
    try {
      console.log(`Trying to connect to ${addr}...`);
      await ipfs.swarm.connect(addr);
      console.log(`Connected to peer at: ${addr}`);
      return true;
    } catch (err) {
      console.warn(`Failed to connect to ${addr}: ${err.message}`);
    }
  }
  console.error("Failed to connect to any provided peer addresses");
  return false;
}

// ----- IPFS CONNECTION TEST -----
(async () => {
  try {
    const id = await ipfs.id();
    console.log("Connected to local IPFS node!");
    console.log("IPFS NODE:", id.id);
  } catch (err) {
    console.error("Failed to connect to IPFS daemon");
    console.error(err.message);
  }
})();

// Display connected peers every 15 seconds
setInterval(async () => {
  try {
    const peers = await ipfs.swarm.peers();
    console.log(`Connected to ${peers.length} peers via swarm`);
  } catch (err) {
    console.error("Failed to fetch peers:", err.message);
  }
}, 15000);

// ----- DOCUMENT VECTOR HANDLING ----- (Sprint 2)
const VECTOR_PATH = path.join(process.cwd(), "docVector.json");

// Create or update a simple vector with a new CID
async function updateDocVector(newCid) {
  console.log("Updating document vector with new CID: ", newCid);

  let currentVector = {
    version: "v1.0.0",
    cids: [],
    timestamp: new Date().toISOString()
  };

  // Try to read existing vector
  if (fs.existsSync(VECTOR_PATH)) {
    try {
      const data = fs.readFileSync(VECTOR_PATH, "utf8");
      currentVector = JSON.parse(data);
    } catch (err) {
      console.warn("Could not read docVector.json, creating new one...");
    }
  }

  // Create updated version
  const newVersion = {
    version: `v${parseFloat(currentVector.version.slice(1)) + 0.1}`, // v1.0.0 → v1.1.0
    cids: [...currentVector.cids, newCid],
    timestamp: new Date().toISOString()
  };

  // Save back to file (overwrite old)
  fs.writeFileSync(VECTOR_PATH, JSON.stringify(newVersion, null, 2));
  console.log("Document vector updated successfully:", newVersion.version);
}

// -----upload ENDPOINT -----
app.post("/upload", upload.single("file"), async (req, res) => {
  if (!req.file) return res.status(400).send("No file uploaded");

  const clientPeerId = req.body.peerId;
  const clientAddrs = req.body.peerAddrs ? JSON.parse(req.body.peerAddrs) : [];

  if (clientPeerId && clientAddrs.length > 0) {
    discoveredPeers.set(clientPeerId, clientAddrs);
    console.log(`New peer discovered: ${clientPeerId}`);
    console.log("Connecting to peer...");
    await connectToPeer(clientAddrs);
  }

  try {
    // 1. Add file to IPFS
    const result = await ipfs.add(req.file.buffer);
    const cid = result.cid.toString();
    console.log("Uploaded CID:", cid);

    // 2. Update document vector (no pending/confirmation logic)
    await updateDocVector(cid);

    // 3. Broadcast message as before
    const message = JSON.stringify({ type: "NEW_FILE", filename: req.file.originalname, cid });
    await ipfs.pubsub.publish("file-sharing", new TextEncoder().encode(message));

    console.log("Message broadcasted to peers");
    res.json({ cid });

  } catch (err) {
    console.error(err);
    res.status(500).send("Error uploading file to IPFS");
  }
});

// ----- SERVER STARTUP -----
app.listen(PORT, () => {
  console.log(`Server running at http://localhost:${PORT}`);
});
