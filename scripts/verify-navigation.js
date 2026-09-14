#!/usr/bin/env node
// Verify navigation for the symbols the user reported broken:
// CartItemDetails, AppDatabase, fruittieDao, loadMapped, database.
const { spawn } = require('child_process');
const path = require('path');
const fs = require('fs');
const { pathToFileURL } = require('url');

const launcher = path.join(__dirname, '..', 'server', 'build', 'install', 'ase-lsp-server', 'bin', 'ase-lsp-server');
const child = spawn(launcher, [], { stdio: ['pipe', 'pipe', 'pipe'] });
let buf = '';
let nextId = 1;
const pending = new Map();
function send(method, params) {
  const id = nextId++;
  const msg = JSON.stringify({ jsonrpc: '2.0', id, method, params });
  child.stdin.write(`Content-Length: ${Buffer.byteLength(msg)}\r\n\r\n${msg}`);
  return new Promise((res, rej) => { pending.set(id, { res, rej }); setTimeout(() => { if (pending.has(id)) { pending.delete(id); rej(new Error('timeout ' + method)); } }, 60000); });
}
function notify(method, params) {
  const msg = JSON.stringify({ jsonrpc: '2.0', method, params });
  child.stdin.write(`Content-Length: ${Buffer.byteLength(msg)}\r\n\r\n${msg}`);
}
child.stdout.on('data', (c) => {
  buf += c.toString();
  while (true) {
    const m = buf.match(/Content-Length: (\d+)\r\n\r\n/);
    if (!m) break;
    const len = parseInt(m[1], 10); const start = m.index + m[0].length;
    if (buf.length < start + len) break;
    const body = buf.slice(start, start + len); buf = buf.slice(start + len);
    try { const msg = JSON.parse(body); if (msg.id && pending.has(msg.id)) { const p = pending.get(msg.id); pending.delete(msg.id); msg.error ? p.rej(new Error(JSON.stringify(msg.error))) : p.res(msg.result); } } catch {}
  }
});
child.stderr.on('data', () => {});

async function main() {
  const ws = process.env.ASE_TEST_WORKSPACE;
  if (!ws) {
    console.log('[SKIP] verify-navigation requires ASE_TEST_WORKSPACE pointing to the Fruitties project');
    child.kill();
    process.exit(0);
  }
  const file = path.join(ws, 'shared/src/commonMain/kotlin/com/example/fruitties/DataRepository.kt');
  const text = fs.readFileSync(file, 'utf8');
  const lines = text.split(/\r?\n/);
  const uri = pathToFileURL(file).href;

  await send('initialize', { processId: process.pid, rootUri: pathToFileURL(ws).href, capabilities: {} });
  notify('initialized', {});
  notify('textDocument/didOpen', { textDocument: { uri, languageId: 'kotlin', version: 1, text } });

  // Wait for project indexing (background).
  await new Promise((r) => setTimeout(r, 6000));

  const targets = ['CartItemDetails', 'AppDatabase', 'fruittieDao', 'loadMapped', 'database'];
  const results = [];
  for (const t of targets) {
    let pos = null;
    for (let i = 0; i < lines.length && !pos; i++) {
      const m = lines[i].match(new RegExp(`\\b${t}\\b`));
      if (m) pos = { line: i, character: m.index };
    }
    const def = await send('textDocument/definition', { textDocument: { uri }, position: pos });
    const loc = Array.isArray(def) && def[0] ? def[0] : null;
    const ok = !!loc && loc.uri.startsWith('file://');
    results.push({ t, ok, uri: loc ? loc.uri.split('/').slice(-3).join('/') + ':' + loc.range.start.line : 'NULL' });
  }
  const fail = results.filter((r) => !r.ok);
  results.forEach((r) => console.log(`${r.ok ? '[OK]' : '[FAIL]'} ${r.t.padEnd(15)} -> ${r.uri}`));
  await send('shutdown', null);
  notify('exit', null);
  process.exit(fail.length ? 1 : 0);
}
main().catch((e) => { console.error('[FAIL]', e.message); child.kill(); process.exit(1); });