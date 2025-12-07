# IPFSInit - gerir o nó IPFS
    Este módulo cuida apenas de:
    iniciar/configurar o daemon ou API client;
    garantir que o cliente conhece o seu peerID;
    fornecer handlers básicos como add(), cat(), pins, etc.;
    conectar à rede libp2p/IPFS.
    Função: preparar o ambiente IPFS para qualquer operação posterior.
    Sem isto, o cliente nem sequer pode participar na rede IPFS, quanto mais subscrever tópicos.

# IPFSPubSub - gerir mensagens pub/sub
    IPFS fornece libp2p PubSub (GossipSub).
    Este módulo isola:
        subscrição a tópicos (subscribe);
        publicação de mensagens (publish);
        callbacks/event listeners para receber difusão;
        parsing e dispatch de mensagens.
        Função: canal de comunicação distribuído entre peers.
        Sem isto, o cliente não recebe difusão do líder (ex: vetores, commits, heartbeats, eleições).

# ClientMain.java - Função principal do cliente/peer.
    Inicializa todos os serviços locais (IPFSInit, PubSub, TCPClient, etc.).
    Liga o peer à rede.
    Regista callbacks/listeners.
    Arranca threads de background (heartbeat, monitorização, etc.).
    Em resumo: ponto de entrada que orquestra todos os componentes do peer.

# PeerHeartbeatMonitor.java - Monitorização do líder e peers via heartbeats.
    Mantém relógios/timers associados ao líder e peers.
    Marca falhas quando timeouts ocorrem.
    Notifica o sistema para iniciar processo de eleição.
    Em resumo: deteta falhas do líder (RNF3) e dispara recuperação (ex: RAFT).

# TCPClient.java - Comunicação direta com o líder via TCP/REST.
    Usado para requests cliente→líder (envio de ficheiros, pedidos de resposta).
    Implementa chamadas point-to-point não feitas pelo PubSub.
    Suporta operações síncronas que precisam de resposta imediata.
    Em resumo: interface de comunicação direta com o líder (API cliente→sistema).

# VectorIndexService.java - Gestão das estruturas FAISS ou embeddings locais.
    Armazena embeddings temporários (pré-commit).
    Atualiza indexação FAISS após commit do líder.
    Realiza pesquisas locais para RF2 (prompt → documentos relevantes).
    Em resumo: módulo responsável por embeddings, FAISS e consultas de similaridade.