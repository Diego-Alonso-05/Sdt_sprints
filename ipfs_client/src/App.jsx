import { useState, useEffect, useRef } from 'react'; // fixed
import { create } from 'kubo-rpc-client';
import axios from 'axios';

const IPFS_API_URL = 'http://127.0.0.1:5001/api/v0';
const NODE_API_URL = 'http://192.168.1.xxx:3000'; // UPDATE WITH THE LEADER COMPUTER PRIVATE IP

function App() {
  // IPFS connection state
  const ipfsRef = useRef(null); // fixed
  const [peerId, setPeerId] = useState('');
  const [peerAddrs, setPeerAddrs] = useState([]);
  const [isConnected, setIsConnected] = useState(false);
  const [connectionError, setConnectionError] = useState('');

  // PubSub state
  const [notifications, setNotifications] = useState([]);
  const [pubsubError, setPubsubError] = useState('');

  // File upload state
  const [selectedFile, setSelectedFile] = useState(null);
  const [isUploading, setIsUploading] = useState(false);
  const [uploadError, setUploadError] = useState('');
  const [uploadedCID, setUploadedCID] = useState('');

  useEffect(() => {
    const connectToIPFS = async () => {
      try {
        setConnectionError('');
        const ipfs = create({ url: IPFS_API_URL });
        console.log('Attempting to connect to local IPFS node...');

        // Get node information (validates connection + retrieves peer data)
        const nodeInfo = await ipfs.id();
        console.log('Connected to IPFS node:', nodeInfo.id);
        console.log('Peer addresses found:', nodeInfo.addresses.length);

        // Extract peer ID and addresses
        const peerIdentifier = nodeInfo.id;
        const peerMultiaddrs = nodeInfo.addresses;

        // Update all states on successful connection
        ipfsRef.current = ipfs; // fixed
        setIsConnected(true);
        setPeerId(peerIdentifier);
        setPeerAddrs(peerMultiaddrs);
        console.log('IPFS connection successful');

        // Subscribe to PubSub topic to receive notifications
        try {
          console.log('Subscribing to file-sharing topic...');

          await ipfs.pubsub.subscribe('file-sharing', (msg) => {
            try {
              const messageStr = new TextDecoder().decode(msg.data);
              console.log('Raw PubSub message received:', messageStr);

              const notification = JSON.parse(messageStr);
              console.log('Parsed notification:', notification);

              setNotifications(prev => [{
                ...notification,
                receivedAt: new Date().toISOString()
              }, ...prev]);

            } catch (parseError) {
              console.error('Failed to parse PubSub message:', parseError);
              setPubsubError('Failed to parse incoming notification');
            }
          });

          console.log('Successfully subscribed to file-sharing topic');
          setPubsubError('');

        } catch (pubsubError) {
          console.error('Failed to subscribe to PubSub:', pubsubError);
          setPubsubError(pubsubError.message || 'Failed to subscribe to notifications');
        }

      } catch (error) {
        console.error('Failed to connect to IPFS node:', error);
        setIsConnected(false);
        ipfsRef.current = null;
        setConnectionError(error.message || 'Failed to connect to IPFS node');
        setPeerId('');
        setPeerAddrs([]);
      }
    };

    connectToIPFS();

    return () => {
      console.log('Component unmounting - cleanup');
      if (ipfsRef.current) {
        ipfsRef.current.pubsub.unsubscribe('file-sharing')
          .then(() => console.log('Unsubscribed from file-sharing topic'))
          .catch(err => console.error('Error unsubscribing:', err));
      }
    };
  }, []); // empty deps, but safe due to ref

  // File Upload: Selection Handler
  const handleFileSelect = (event) => {
    const file = event.target.files[0];

    if (!file) {
      setSelectedFile(null);
      return;
    }

    // // File Upload: Validation (size limit 100MB)
    const maxSize = 100 * 1024 * 1024; // 100MB in bytes
    if (file.size > maxSize) {
      setUploadError(`File too large. Maximum size is 100MB`);
      setSelectedFile(null);
      return;
    }

    setSelectedFile(file);
    setUploadError('');
    setUploadedCID('');
    console.log('File selected:', file.name, `(${(file.size / 1024).toFixed(2)} KB)`);
  };

  //// File Upload: Upload to Node.js API
  const handleUpload = async () => {
    if (!selectedFile) {
      setUploadError('Please select a file first');
      return;
    }

    if (!isConnected || !peerId) {
      setUploadError('Not connected to IPFS node');
      return;
    }

    try {
      setIsUploading(true);
      setUploadError('');
      setUploadedCID('');

      console.log('Preparing upload...');
      console.log('Peer ID:', peerId);
      console.log('Peer Addresses:', peerAddrs);

      const formData = new FormData(); // Create FormData with file and peer info
      formData.append('file', selectedFile);
      formData.append('peerId', peerId);
      formData.append('peerAddrs', JSON.stringify(peerAddrs)); // Important: stringify addresses

      console.log('Uploading to:', `${NODE_API_URL}/upload`);

      // POST to Node.js API
      const response = await axios.post(`${NODE_API_URL}/upload`, formData, {
        headers: {
          'Content-Type': 'multipart/form-data',
        },
      });

      console.log('Upload response:', response.data);

      // Display returned CID
      if (response.data.cid) {
        setUploadedCID(response.data.cid);
        console.log('File uploaded successfully! CID:', response.data.cid);
      }

      // Clear file selection after successful upload
      setSelectedFile(null);
      document.getElementById('fileInput').value = '';

    } catch (error) {
      console.error('Upload failed:', error);

      if (error.response) {
        setUploadError(`Upload failed: ${error.response.data.error || error.response.statusText}`);
      } else if (error.request) {
        setUploadError('Upload failed: Cannot reach server. Check if Node.js API is running.');
      } else {
        setUploadError(`Upload failed: ${error.message}`);
      }
    } finally {
      setIsUploading(false);
    }
  };

  return (
    <div style={{ padding: '20px', fontFamily: 'Arial, sans-serif', maxWidth: '1200px', margin: '0 auto' }}>
      <h1>IPFS File Sharing Client</h1>

      {/* Connection Status */}
      <div style={{ marginBottom: '20px', padding: '15px', backgroundColor: '#f0f0f0', borderRadius: '5px' }}>
        <h2>Connection Status</h2>
        <p><strong>IPFS Node:</strong> {isConnected ? 'Connected' : 'Disconnected'}</p>
        <p><strong>Peer ID:</strong> {String(peerId) || 'N/A'}</p>
        <p><strong>Addresses:</strong> {peerAddrs.length} found</p>
        {connectionError && <p style={{color: 'red'}}><strong>Error:</strong> {connectionError}</p>}
        {pubsubError && <p style={{color: 'orange'}}><strong>PubSub Error:</strong> {pubsubError}</p>}
      </div>

      {/* File Upload Section */}
      <div style={{ marginBottom: '20px', padding: '15px', backgroundColor: '#e8f4f8', borderRadius: '5px' }}>
        <h2>Upload File</h2>

        <div style={{ marginBottom: '15px' }}>
          <input
            id="fileInput"
            type="file"
            onChange={handleFileSelect}
            disabled={!isConnected || isUploading}
            style={{ marginBottom: '10px' }}
          />
        </div>

        {selectedFile && (
          <div style={{ marginBottom: '15px', padding: '10px', backgroundColor: '#fff', borderRadius: '3px' }}>
            <p><strong>Selected File:</strong> {selectedFile.name}</p>
            <p><strong>Size:</strong> {(selectedFile.size / 1024).toFixed(2)} KB</p>
            <p><strong>Type:</strong> {selectedFile.type || 'Unknown'}</p>
          </div>
        )}

        <button
          onClick={handleUpload}
          disabled={!selectedFile || !isConnected || isUploading}
          style={{
            padding: '10px 20px',
            backgroundColor: (!selectedFile || !isConnected || isUploading) ? '#ccc' : '#4CAF50',
            color: 'white',
            border: 'none',
            borderRadius: '5px',
            cursor: (!selectedFile || !isConnected || isUploading) ? 'not-allowed' : 'pointer',
            fontSize: '16px'
          }}
        >
          {isUploading ? 'Uploading...' : 'Upload to Network'}
        </button>

        {uploadError && (
          <p style={{ color: 'red', marginTop: '10px' }}><strong>Error:</strong> {uploadError}</p>
        )}

        {uploadedCID && (
          <div style={{ marginTop: '15px', padding: '10px', backgroundColor: '#d4edda', borderRadius: '3px' }}>
            <p style={{ color: '#155724' }}><strong>Upload Successful!</strong></p>
            <p><strong>CID:</strong> <code>{uploadedCID}</code></p>
          </div>
        )}
      </div>

      {/* Notifications Section */}
      <div style={{ marginBottom: '20px' }}>
        <h2>Network Notifications ({notifications.length})</h2>
        <div>
          {notifications.length === 0 ? (
            <p style={{color: '#666'}}>No notifications yet. Waiting for broadcasts...</p>
          ) : (
            notifications.map((notif, idx) => (
              <div key={idx} style={{
                border: '1px solid #ccc',
                margin: '10px 0',
                padding: '15px',
                borderRadius: '5px',
                backgroundColor: '#f9f9f9'
              }}>
                <pre style={{ overflow: 'auto', fontSize: '12px', margin: 0 }}>
                  {JSON.stringify(notif, null, 2)}
                </pre>
              </div>
            ))
          )}
        </div>
      </div>
    </div>
  );
}

export default App;
