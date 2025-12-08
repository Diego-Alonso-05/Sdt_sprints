SDT_NEWPROJECT
├── ClientPeer
│   ├── src
│   │   ├── ClientMain.java
│   │   ├── IPFSInit_Client.java
│   │   ├── PeerHeartbeatMonitor.java
│   │   ├── RaftElectionService.java
│   │   ├── TCPClient.java
|   |   ├── FileManager_client.java
│   │   └── VectorIndexService.java
│   ├── .gitignore
│   └── ClientPeer.iml
│
├── common
│   ├── src
│       └── common.iml
│
├── ServerLeader
|   ├── .venv
│   ├── embed/embed.py
|   ├── uploads(Files in format .bin)
│   ├── src
│   │   ├── Connection.java
│   │   ├── Embeddings.java
│   │   ├── FileManager_server.java
│   │   ├── IPFSInit_Server.java
│   │   ├── LeaderHeartbeatService.java
│   │   ├── LeaderState.java
│   │   ├── ServerMain.java
│   │   └── TCPServer.java
│   ├── .gitignore
│   └── ServerLeader.iml
│
├── libs/org.eclipse.paho.client.mqttv3-1.2.5.jar
|
├── .gitignore
├── README.md
└── SDt_newProject.iml
