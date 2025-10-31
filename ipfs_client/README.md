### ----- Client Requirements -----###
- Make sure you have installed:
*   node and npm (check with: [npm --version] and [node --version])
*   ipfs (check with: [ipfs --version])
ipsf + kubo(RPC API) for cli configuration and integration with react code (check with: [ipfs --version])

## --- Client SETUP -- ##
*   After cloning the whole project inside a terminal you need to install all the dependencies for the API to be able to run, for that do:
cd ipfs_client
npm install
*   Insert the following rules to configure IPFS CORS on terminal:
ipfs config --json API.HTTPHeaders.Access-Control-Allow-Origin "[\"http://localhost:3000\", \"http://127.0.0.1:3000\"]"
ipfs config --json API.HTTPHeaders.Access-Control-Allow-Methods "[\"GET\", \"POST\", \"PUT\"]"

# To run the client:
- Before running the client app make sure you have running IPFS:
ipfs daemon --enable-pubsub-experiment
- Then run react client:
npm start

# Data that Client sends when uploading to the nodeJS API
   * File: The file to upload
   * peerId: Their IPFS node peer ID (get with ipfs id)
   * peerAddrs: JSON string array of their node's multiaddresses

# API Endpoints
POST /upload - Upload file and announce peer presence
   * Form data: file (the file)
   * Body: peerId (string), peerAddrs (JSON string array)
   * Returns: { cid: "Qm..." }

# IPFS NODE
* This project connects to an **external IPFS daemon**  using kubo-rpc-client.
* Runs inside the browser sandbox, so CORS (Cross-Origin Resource Sharing) rules are enforced by the browser
* The browser enforces CORS (Runs inside the browser sandbox):
* Even though the React app connects to a local IPFS daemon (http://127.0.0.1:5001/api/v0), the browser treats this as a cross-origin request, resulting in a CORS restriction.
To allow browser access, the IPFS daemon must be configured with appropriate CORS headers using the rules mentioned above in this file. These rules will persist stored inside ~/.ipfs .

------------------------------------------------------------------

## Technical REACT EXECUTION
    REACT EXECUTION:
1. React calls App() (*0ms* User opens page)
2. State initialized: isConnected=false, peerId=''
3. useEffect registered (not run yet)
4. JSX rendered: <div>Phase 2 Logic Complete</div>

5. useEffect runs (*1ms* Browser displays page)
    - connectToIPFS() called
        - Async operation starts (waiting for IPFS node)

6. ipfs.id() returns data (*50ms*)
─ setIpfsNode(ipfs) called
─ setIsConnected(true) called
─ setPeerId('12D3Koo...') called
─ setPeerAddrs([...]) called
    ─ React batches these updates

7. React calls App() AGAIN (*51ms* React detects state changed  )
    - State returns NEW values: isConnected=true, peerId='12D3Koo...'
    - useEffect sees deps=[] (no change) → doesn't run
    - JSX rendered: <div>Phase 2 Logic Complete</div>
(same JSX, but could show different content based on state)

8. Browser displays updated page (*52ms*)
- App is now in "connected" state, ready for user interaction

### REACT LEARNINGS

# useState -> a React Hook that stores data that can change
const [value, setValue] = useState(initialValue);
- value: the current value of the state
- setValue: function to update the state
- initialValue: starting value

# useEffect->
useEffect(() => {
// Code here runs AFTER component renders
return () => {
    // Cleanup code (runs when component unmounts)
};
}, []); // Dependencies array
- Run side effects (API calls, subscriptions, timers)
- Runs AFTER the component renders to the screen
- Cleanup function runs when component is removed
- Dependency Array []:
Empty [] = run once when component first mounts
[value] = run when value changes
No array = run on every render (usually not what you want)

