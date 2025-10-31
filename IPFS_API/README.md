### ----- API Requirements ----- ##
- Make sure you have installed:
node and npm (check with: [npm --version] and [node --version])
ipfs (check with: [ipfs --version])

## Architecture
React Client → Express API → Kubo Daemon → IPFS Network (PubSub)
                                    ↑
                               CLI Access

## --- API SETUP -- ##
- After cloning the whole project inside a terminal you need to install all the dependencies for the API to be able to run, for that do:
cd IPFS_API
npm install

# To run the server:
- Before running the server app run IPFS:
Launch ipfs desktop app or use ipfs daemon in terminal
- Then run the NodeJS server:
npm run dev

# IPFS NODE
* This project connects to an **external IPFS daemon** (Kubo/IPFS Desktop) using kubo-rpc-client
* No CORS enforcement (Runs on the local machine (outside a browser))
- Commands:
ipfs init //Initialize IPFS
ipfs daemon //Sets node on and starts the IPFS RPC API at `http://127.0.0.1:5001`
ipfs pubsub sub file-sharing //Subscribe to PubSub messages (optional - for terminal monitoring)


- When running npm install npm extracts these dependencies and installs them locally on the project (node_modules)
# Dependencies on package.json:
    express - Web server framework
    multer - File upload handling
    kubo-rpc-client - RPC client for Kubo daemon
    nodemon (dev) - Auto-restart during development

- Observations:
* In package.json-> `"type": "module"` is **required** for ES modules
* `kubo-rpc-client` connects to external Kubo daemon via API endpoint at `http://127.0.0.1:5001/api/v0`

# Key Features:

## Dynamic Peer Discovery
- When clients upload files, they send their IPFS peer ID and addresses
- API automatically stores and connects to discovered peers
- No manual peer configuration needed

## PubSub Messaging (libp2p GossipSub)
- Uses IPFS/libp2p's built-in PubSub for message broadcasting
- Topic: 'file-sharing'
- When a file is uploaded, a message is broadcast to all connected peers
- React clients can subscribe to receive real-time notifications

## Peer Monitoring
- Displays connected peers every 15 seconds in server console
- `/peers` endpoint provides list of all discovered peers

## How It Works:
1. Client uploads file with their peer info (peerId + addresses)
2. API stores peer info in `discoveredPeers` Map
3. API attempts connection to the peer's addresses
4. File is added to IPFS and CID is returned
5. PubSub message is broadcast to all connected peers
6. React clients subscribed to 'file-sharing' topic receive notification

## Network Architecture:
- Uses Kademlia DHT for network formation (built into IPFS)
- Uses GossipSub protocol for group message broadcasting
- Direct peer-to-peer connections established via swarm
- All peers must run their own IPFS node (IPFS Desktop)