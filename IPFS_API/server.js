import express from "express";
import multer from "multer";
import { create } from "kubo-rpc-client";
import cors from "cors";


const app = express();
const PORT = 3000;
app.use(cors()); // Allow CORS to permit client AXIOS requests
app.use(express.json()); // Parse JSON bodies


// Handle file storage in cache(ram)
const upload = multer({ storage: multer.memoryStorage() });

// Connect to local IPFS node through Kubo daemon
const ipfs = create({
  url: 'http://127.0.0.1:5001/api/v0'
});

// Stores all clients that have connected to the API via http
const discoveredPeers = new Map(); // Store discovered peers(map structure: peerId -> [addresses])

// Attempts to connect to a peer using their multiaddresses(a peer can have multiple addresses)
// Atempts to conncect to each address until one succeeds
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


// Test IPFS connection on startup
(async () => {
  try {
    const id = await ipfs.id();
    console.log("Connected to IPFS local node!");
    console.log("IPFS NODE:", id.id);
  } catch (err) {
    console.error("Failed to connect to IPFS daemon");
    console.error(err.message);
  }
})();

// Display number of connected peers every 15 seconds for monitoring
setInterval(async () => {
  try {
    const peers = await ipfs.swarm.peers();
    console.log(`Connected to ${peers.length} peers via swarm`);
  } catch (err) {
    console.error("Failed to fetch peers:", err.message);
  }
}, 15000);

app.post("/upload", upload.single("file"), async (req, res) => {
  if (!req.file) return res.status(400).send("No file uploaded");

  // Client send their IPFS node ID and addresses when uploading the file
  const clientPeerId = req.body.peerId; // Extract Peer ID from request body
  const clientAddrs = req.body.peerAddrs ? JSON.parse(req.body.peerAddrs) : []; // Extract Peer addresses from request body

  // When a client uploads a file, they announce their Peer ID
  // To make sure we have this Peer added we store their info and connect with it
  if (clientPeerId && clientAddrs.length > 0) { // verifies if values of clientPeerId and clientAddrs.length are not null/undefined/etc..
    discoveredPeers.set(clientPeerId, clientAddrs); // Add peer to or local map (or update if already exists)
    console.log(`New peer discovered: ${clientPeerId}`);
    console.log('Connecting to Peer..');
    await connectToPeer(clientAddrs); // Immediately attempt connection to the new peer
  }

  try {
    // Add uploaded file to IPFS
    const result = await ipfs.add(req.file.buffer);
    const cid = result.cid.toString();

    // Broadcast message to all peers via PubSub
    // All connected peers subscribed to 'file-sharing' topic will receive this
    const message = JSON.stringify({ type: "NEW_FILE", filename: req.file.originalname, cid });
    await ipfs.pubsub.publish('file-sharing', new TextEncoder().encode(message));

    console.log("Uploaded CID:", cid);
    console.log("Message broadcasted to peers");

    res.json({ cid });
  } catch (err) {
    console.error(err);
    res.status(500).send("Error uploading file to IPFS");
  }
});

app.listen(PORT, () => {
  console.log(`Server running at http://localhost:${PORT}`);
});