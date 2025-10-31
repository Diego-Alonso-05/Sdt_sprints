Work in development by: Rodrigo Rolo - estgv18757; Diego Alonso - pv33986; David Gonzalez - pv33971;
SDt Sprint 1 done but incomplete(26/10/2025) - old server.js and Sprint 1.pdf files saved in Old_Sprint1 subfolder.

SDt_Project/
│
├── server/  # Express API (file uploads + pubsub routing)
├── client/  # React app (file upload UI + CID display)
├── optional: (kubo/   # IPFS swarm config & setup docs)
│
├── .gitignore
└── README.md



##  Overview
The nodeJS API will be running in a "leader" machine with its IPFS node.
Every client will run in another computer under the same LAN, using this react client
altogether with ISPF desktop + kubo.
Client uploads a file with HTTP to the API.
The API will receives and publishs this file to ipfs through its node, and will
brodcasting a message to all conected peers subscribed to 'file-sharing' topic,
which the client machine must read