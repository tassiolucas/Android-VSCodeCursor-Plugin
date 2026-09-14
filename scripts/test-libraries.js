#!/usr/bin/env node
/**
 * End-to-end test for EXTERNAL LIBRARY indexing (Phase 3).
 *
 * Requires ASE_TEST_WORKSPACE pointing at a real Gradle project. It:
 *   1. starts the server,
 *   2. opens a .kt file that uses an external library class,
 *   3. waits (polling workspace/symbol) until the Gradle classpath resolution
 *      + ASM library indexing finish in background,
 *   4. requests textDocument/definition on the library usage and asserts the
 *      result points at an `ase-lib:` URI.
 */
const { spawn } = require('child_process');
const path = require('path');
const fs = require('fs');
const { pathToFileURL } = require('url');

const serverDir = path.join(__dirname, '..', 'server', 'build', 'install', 'ase-lsp-server', 'bin');
const isWin = process.platform === 'win32';
const launcher = isWin
  ? path.join(serverDir, 'ase-lsp-server.bat')
  : path.join(serverDir, 'ase-lsp-server');

const child = spawn(isWin ? `"${launcher}"` : launcher, [], {
  stdio: ['pipe', 'pipe', 'pipe'],
  shell: isWin,
});

let buf = '';
let nextId = 1;
const pending = new Map();

function send(method, params) {
  const id = nextId++;
  const msg = JSON.stringify({ jsonrpc: '2.0', id, method, params });
  child.stdin.write(`Content-Length: ${Buffer.byteLength(msg)}\r\n\r\n${msg}`);
  return new Promise((resolve, reject) => {
    pending.set(id, { resolve, reject });
    setTimeout(() => {
      if (pending.has(id)) {
        pending.delete(id);
        reject(new Error(`Timeout waiting for ${method}`));
      }
    }, 150000);
  });
}

function sendNotify(method, params) {
  const msg = JSON.stringify({ jsonrpc: '2.0', method, params });
  child.stdin.write(`Content-Length: ${Buffer.byteLength(msg)}\r\n\r\n${msg}`);
}

child.stdout.on('data', (chunk) => {
  buf += chunk.toString();
  while (true) {
    const m = buf.match(/Content-Length: (\d+)\r\n\r\n/);
    if (!m) break;
    const len = parseInt(m[1], 10);
    const start = m.index + m[0].length;
    if (buf.length < start + len) break;
    const body = buf.slice(start, start + len);
    buf = buf.slice(start + len);
    try {
      const msg = JSON.parse(body);
      if (msg.id && pending.has(msg.id)) {
        const p = pending.get(msg.id);
        pending.delete(msg.id);
        if (msg.error) p.reject(new Error(JSON.stringify(msg.error)));
        else p.resolve(msg.result);
      }
    } catch (e) {
      console.error('Bad message:', body);
    }
  }
});

child.stderr.on('data', (d) => process.stderr.write(d));

function findAllKtFiles(root) {
  const files = [];
  const stack = [root];
  while (stack.length) {
    const dir = stack.pop();
    let entries;
    try {
      entries = fs.readdirSync(dir, { withFileTypes: true });
    } catch {
      continue;
    }
    for (const e of entries) {
      if (e.name === 'build' || e.name.startsWith('.')) continue;
      const full = path.join(dir, e.name);
      if (e.isDirectory()) stack.push(full);
      else if (e.name.endsWith('.kt')) files.push(full);
    }
  }
  return files;
}

// Find a .kt file that imports an external library class, and a usage position.
function findLibraryUsage(root) {
  for (const file of findAllKtFiles(root)) {
    const text = fs.readFileSync(file, 'utf8');
    const lines = text.split(/\r?\n/);
    for (let i = 0; i < lines.length; i++) {
      const m = lines[i].match(/^import\s+([\w.]+)\.([A-Z][\w]*)\s*$/);
      if (!m) continue;
      const simple = m[2];
      // Find the simple name used later in the file (a usage site).
      for (let j = 0; j < lines.length; j++) {
        if (j === i) continue;
        const um = lines[j].match(new RegExp(`\\b(${simple})\\b`));
        if (um) {
          return { uri: pathToFileURL(file).href, text, line: j, character: um.index, simple };
        }
      }
    }
  }
  return undefined;
}

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

async function main() {
  console.log('== ASE Library Indexing test ==\n');

  const workspace = process.env.ASE_TEST_WORKSPACE;
  if (!workspace) throw new Error('Set ASE_TEST_WORKSPACE to a real Gradle project');
  if (!fs.existsSync(workspace)) throw new Error(`Workspace not found: ${workspace}`);

  const usage = findLibraryUsage(workspace);
  if (!usage) throw new Error('No external library usage (import + call) found in workspace');

  const rootUri = pathToFileURL(workspace).href;
  console.log(`Workspace: ${workspace}`);
  console.log(`Target: ${path.basename(usage.uri)} usage of '${usage.simple}' (line ${usage.line})`);

  const init = await send('initialize', {
    processId: process.pid,
    rootUri,
    capabilities: {},
  });
  console.log('[OK] initialize');
  sendNotify('initialized', {});

  sendNotify('textDocument/didOpen', {
    textDocument: { uri: usage.uri, languageId: 'kotlin', version: 1, text: usage.text },
  });
  console.log('[OK] didOpen');

  // Poll until the library index has the target symbol (background indexing runs).
  let found = false;
  for (let attempt = 0; attempt < 60; attempt++) {
    const ws = await send('workspace/symbol', { query: usage.simple });
    const hits = Array.isArray(ws) ? ws.filter((s) => s.name === usage.simple) : [];
    const libHit = hits.find((s) => s.location && s.location.uri.startsWith('ase-lib:'));
    if (libHit) {
      console.log(`[OK] library symbol indexed after ~${attempt * 3}s: ${libHit.name} (${libHit.location.uri.slice(0, 80)})`);
      found = true;
      break;
    }
    await sleep(3000);
  }
  if (!found) {
    throw new Error(`Library symbol '${usage.simple}' not found in index after polling`);
  }

  // Definition on the usage should resolve to an external library location.
  const def = await send('textDocument/definition', {
    textDocument: { uri: usage.uri },
    position: { line: usage.line, character: usage.character },
  });
  const defUri = Array.isArray(def) && def[0] ? def[0].uri : null;
  console.log('[OK] definition ->', JSON.stringify(def).slice(0, 120));
  if (!defUri || !defUri.startsWith('ase-lib:')) {
    throw new Error(`Expected ase-lib: definition, got ${defUri}`);
  }

  // Decompile the target class through the server command and assert source.
  const raw = decodeURIComponent(defUri.replace(/^ase-lib:/, ''));
  const bang = raw.indexOf('!');
  const jarPath = raw.slice(0, bang);
  const className = raw.slice(bang + 1).split('#')[0];
  const decompiled = await send('workspace/executeCommand', {
    command: 'ase.decompile',
    arguments: [{ jarPath, className }],
  });
  console.log('[OK] decompile ->', (decompiled || '').slice(0, 120).replace(/\n/g, ' '));
  if (!decompiled || typeof decompiled !== 'string' || decompiled.length < 50) {
    throw new Error('Decompiled source is empty/short');
  }
  if (!decompiled.includes(className.replace(/\//g, '.'))) {
    throw new Error(`Decompiled source does not mention ${className}`);
  }

  await send('shutdown', null);
  sendNotify('exit', null);
  console.log('\n== ALL LIBRARY CHECKS PASSED ==');
  process.exit(0);
}

main().catch((e) => {
  console.error('\n[FAIL]', e.message);
  child.kill();
  process.exit(1);
});