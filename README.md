# SDt Project - IPFS Decentralized File Sharing System

Work in development by: Rodrigo Rolo (estgv18757), Diego Alonso (pv33986), David Gonzalez (pv33971)

SDt Sprint 1 done but incomplete(26/10/2025) - old server.js and Sprint 1.pdf files saved in Old_Sprint1 subfolder.

## Overview

This project implements a decentralized file-sharing system using IPFS (InterPlanetary File System) with a client-server architecture. The system enables peer-to-peer file distribution across a Local Area Network (LAN) with real-time notifications via PubSub messaging. The system can be scalable to run in larger network.

### Architecture

React Client → Express API → Kubo Daemon → IPFS Network (PubSub)
     ↓              ↓              ↑
 Browser UI    File Upload    CLI Access

The Node.js API runs on a "leader" machine with its IPFS node. Each client operates on separate computers within the same LAN, running the React client alongside IPFS + Kubo. Clients upload files via HTTP to the API, which publishes them to IPFS through its local node and broadcasts messages to all connected peers subscribed to the `file-sharing` topic.


## Project Structure

SDt_Project/
│
├── server/          # Express API (file uploads + PubSub routing)
├── client/          # React app (file upload UI + CID display)
├── Old_Sprint1/     # Archived Sprint 1 files
│
├── .gitignore
└── README.md

## Requirements

Both client and server require:
- **Node.js** and **npm** (verify: `npm --version`, `node --version`)
- **IPFS** with **Kubo** (verify: `ipfs --version`)


    **Setup Instructions**
### Server Setup
Take a look at IPFS_API/README.md
### Client Setup
Take a look at ipfs_client/README.md



## How It Works
### File Upload Flow

1. Client uploads file with their peer info (peer ID + multiaddresses)
2. API stores peer info in `discoveredPeers` Map
3. API attempts connection to the peer's addresses to make sure it is in its peer pool
4. File is added to IPFS and CID (Content Identifier) is returned
5. PubSub message is broadcast to all peers on the `file-sharing` topic
6. React clients subscribed to the topic receive real-time notifications

### Network Features

- **Dynamic Peer Discovery:** Clients automatically share their peer information during uploads; no manual configuration needed
- **PubSub Messaging:** Uses IPFS/libp2p's GossipSub protocol for real-time message broadcasting
- **Peer Monitoring:** Server displays connected peers every 15 seconds;
- **DHT & Swarm:** Uses Kademlia DHT for network formation and direct peer-to-peer connections


## API Endpoints
### `POST /upload`
Upload file and announce peer presence

**Request:**
- Form data: `file` (the file to upload)
- Body:
  - `peerId` (string): IPFS node peer ID
  - `peerAddrs` (JSON string array): Node's multiaddresses

**Response:**
```json
{ "cid": "Qm..." }
```

## Technical Details
- All peers must run their own IPFS node (IPFS Desktop or daemon)
### Server
- **Express.js** web server framework
- **Multer** for file upload handling
- **kubo-rpc-client** connects to external Kubo daemon at `http://127.0.0.1:5001/api/v0`
- Runs outside browser (no CORS restrictions)
- ES modules enabled (`"type": "module"` in package.json)

### Client
- **React** for UI components
- **kubo-rpc-client** for IPFS connectivity
- Runs in browser sandbox (CORS rules enforced)
- Uses `useState` for state management and `useEffect` for IPFS connection lifecycle
- Connects to local IPFS daemon at `http://127.0.0.1:5001/api/v0`
- Browser CORS restrictions require IPFS daemon configuration for client
- PubSub requires `--enable-pubsub-experiment` flag when starting daemon

### IPFS Configuration
- Both client and server connect to external IPFS daemons (Kubo/IPFS Desktop)
- PubSub topic: `file-sharing`
- Monitor PubSub in terminal: `ipfs pubsub sub file-sharing`






