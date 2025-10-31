import { spawn, exec } from 'child_process';
import { promisify } from 'util';

const execAsync = promisify(exec);

let ipfsDaemon = null;
let reactApp = null;

// Check if IPFS daemon is already running
async function isIPFSRunning() {
  try {
    await execAsync('ipfs swarm peers', { timeout: 2000 });
    return true;
  } catch (error) {
    return false;
  }
}

// Stop existing IPFS daemon
async function stopExistingIPFS() {
  console.log('Stopping existing IPFS daemon...');

  try {
    await execAsync('ipfs shutdown', { timeout: 5000 });     // Try graceful shutdown first
    console.log('Existing IPFS daemon stopped gracefully');

    await new Promise(resolve => setTimeout(resolve, 1000));     // Wait a moment for cleanup
  } catch (error) {
    console.log('Graceful shutdown failed, attempting force kill...');
    try {
      if (process.platform === 'win32') {
        await execAsync('taskkill /F /IM ipfs.exe', { timeout: 3000 });     // If graceful shutdown fails, try force kill
      } else {
        await execAsync('killall -9 ipfs', { timeout: 3000 });
      }
      console.log('IPFS daemon forcefully stopped');
      await new Promise(resolve => setTimeout(resolve, 2000));
    } catch (killError) {
      console.log('Could not kill IPFS (may not be running)');
    }
  }
}

// Configure IPFS CORS settings
async function configureIPFS() {
  console.log('🔧 Configuring IPFS CORS settings...');

  try {
    await execAsync('ipfs config --json API.HTTPHeaders.Access-Control-Allow-Origin "[\\"http://localhost:3000\\", \\"http://127.0.0.1:3000\\"]"');
    await execAsync('ipfs config --json API.HTTPHeaders.Access-Control-Allow-Methods "[\\"GET\\", \\"POST\\", \\"PUT\\"]"');
    console.log('IPFS CORS configured successfully');
  } catch (error) {
    console.error('Failed to configure IPFS:', error.message);
    throw error;
  }
}

// Check if IPFS is initialized
async function checkIPFSInitialized() {
  try {
    await execAsync('ipfs config show', { timeout: 2000 });
    return true;
  } catch (error) {
    return false;
  }
}

// Start IPFS daemon
async function startIPFSDaemon() {
  console.log('Starting IPFS daemon with PubSub...');

  return new Promise((resolve, reject) => {
    ipfsDaemon = spawn('ipfs', ['daemon', '--enable-pubsub-experiment'], {
      stdio: 'pipe'
    });

    ipfsDaemon.stdout.on('data', (data) => {
      const output = data.toString();
      console.log(`[IPFS] ${output.trim()}`);

      if (output.includes('Daemon is ready')) { // Daemon is ready when it prints "Daemon is ready"
        resolve();
      }
    });

    ipfsDaemon.stderr.on('data', (data) => {
      const errorMsg = data.toString().trim();
      console.error(`[IPFS ERROR] ${errorMsg}`);

      // Check for lock error
      if (errorMsg.includes('lock') || errorMsg.includes('someone else has the lock')) {
        reject(new Error('IPFS daemon is already running (lock detected)'));
      }
    });

    ipfsDaemon.on('error', (error) => {
      console.error('Failed to start IPFS daemon:', error.message);
      reject(error);
    });

    ipfsDaemon.on('close', (code) => {
      if (code !== 0 && code !== null) {
        console.log(`IPFS daemon exited with code ${code}`);
      }
      cleanup();
    });

    // Timeout after 30 seconds
    setTimeout(() => {
      if (!ipfsDaemon.killed) {
        reject(new Error('IPFS daemon startup timeout'));
      }
    }, 30000);
  });
}

// Start React development server
async function startReactApp() {
  console.log('⚛️  Starting React app...');

  reactApp = spawn('npm', ['start'], {
    stdio: 'inherit',
    shell: true
  });

  reactApp.on('error', (error) => {
    console.error('Failed to start React app:', error.message);
    cleanup();
  });

  reactApp.on('close', (code) => {
    console.log(`React app exited with code ${code}`);
    cleanup();
  });
}

// Cleanup function
function cleanup() {
  console.log('\nCleaning up...');

  if (reactApp && !reactApp.killed) {
    console.log('Stopping React app...');
    reactApp.kill();
  }

  if (ipfsDaemon && !ipfsDaemon.killed) {
    console.log('Stopping IPFS daemon...');
    ipfsDaemon.kill();
  }

  process.exit(0);
}

// Handle shutdown signals
process.on('SIGINT', cleanup);
process.on('SIGTERM', cleanup);
process.on('exit', cleanup);

(async () => {
  try {
    console.log('🎬 Starting IPFS Client Setup...\n');

    // Check if IPFS is initialized
    const isInitialized = await checkIPFSInitialized();
    if (!isInitialized) {
      console.error('IPFS is not initialized!');
      console.log('📝 Run "ipfs init" first, then try again.');
      process.exit(1);
    }

    // Step 1: Check and stop existing IPFS daemon
    const alreadyRunning = await isIPFSRunning();
    if (alreadyRunning) {
      await stopExistingIPFS();
    }

    // Step 2: Configure IPFS
    await configureIPFS();

    // Step 3: Start IPFS daemon
    await startIPFSDaemon();
    console.log('IPFS daemon is running\n');

    // Step 4: Start React app
    await startReactApp();
    console.log('React app is starting...\n');
    console.log('Press Ctrl+C to stop all services\n');

  } catch (error) {
    console.error('Startup failed:', error.message);
    cleanup();
  }
})();
