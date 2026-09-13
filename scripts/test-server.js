const net = require('net');
const child_process = require('child_process');

const serverPath = 'D:\\Projetos\\CodeCursor Android Plugin Resolver\\server\\build\\install\\ase-lsp-server\\bin\\ase-lsp-server.bat';

console.log('Iniciando servidor LSP...');
const proc = child_process.spawn('cmd.exe', ['/c', serverPath], {
    stdio: ['pipe', 'pipe', 'pipe']
});

let messageId = 0;

function sendRequest(method, params) {
    const id = ++messageId;
    const msg = {
        jsonrpc: '2.0',
        id: id,
        method: method,
        params: params
    };
    const body = JSON.stringify(msg);
    const header = `Content-Length: ${Buffer.byteLength(body)}\r\n\r\n`;
    console.log(`>>> ${method} (id=${id})`);
    proc.stdin.write(header + body);
    return id;
}

const buffers = [];
proc.stdout.on('data', (data) => {
    buffers.push(data);
    const buffer = Buffer.concat(buffers);
    const str = buffer.toString('utf8');
    
    // Parse LSP messages
    const match = str.match(/Content-Length: (\d+)\r\n\r\n(.*)/s);
    if (match) {
        const length = parseInt(match[1]);
        const body = match[2];
        if (body.length >= length) {
            const json = body.substring(0, length);
            try {
                const msg = JSON.parse(json);
                console.log('<<< Response:', JSON.stringify(msg, null, 2));
                if (msg.id === 1 && msg.result) {
                    console.log('✅ Initialize succeeded!');
                    sendRequest('shutdown', {});
                } else if (msg.id === 2) {
                    console.log('✅ Shutdown succeeded, sending exit...');
                    proc.kill();
                    process.exit(0);
                }
            } catch (e) {
                console.error('Failed to parse:', json.substring(0, 100));
            }
            buffers.length = 0;
            buffer.drain = true;
        }
    }
});

proc.stderr.on('data', (data) => {
    console.error('STDERR:', data.toString());
});

proc.on('exit', (code) => {
    console.log(`Server exited with code ${code}`);
});

// Send initialize
sendRequest('initialize', {
    processId: process.pid,
    rootUri: 'file:///d:/Projetos/KMP/Weave%20App',
    capabilities: {}
});

setTimeout(() => {
    console.error('Timeout waiting for response');
    process.exit(1);
}, 10000);
