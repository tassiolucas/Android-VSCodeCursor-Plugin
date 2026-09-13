#!/usr/bin/env node
/**
 * Smoke test for the ASE LSP server.
 *
 * Spawns the Kotlin server, sends real LSP messages over stdio:
 *   initialize -> initialized -> didOpen -> hover -> definition -> shutdown -> exit
 *
 * Usage: node test-lsp.js [path-to-server-jar]
 */
const { spawn } = require('child_process');
const path = require('path');
const fs = require('fs');

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
    }, 15000);
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

const sampleKotlin = `package com.example.demo

/**
 * Greets a person.
 */
fun greet(name: String): String {
    return "Hello, \$name!"
}

class Greeter {
    fun sayHello(): String = greet("World")
}

fun main() {
    val g = Greeter()
    println(g.sayHello())
}
`;

async function main() {
  console.log('== ASE LSP Server smoke test ==\n');

  // 1. initialize
  const initResult = await send('initialize', {
    processId: process.pid,
    rootUri: 'file:///tmp/demo',
    capabilities: {},
  });
  console.log('[OK] initialize ->', JSON.stringify(initResult.capabilities).slice(0, 160));

  sendNotify('initialized', {});

  // 2. didOpen
  const uri = 'file:///tmp/demo/Hello.kt';
  sendNotify('textDocument/didOpen', {
    textDocument: { uri, languageId: 'kotlin', version: 1, text: sampleKotlin },
  });
  console.log('[OK] didOpen (Hello.kt)');

  // 3. hover over "greet" (line 5, col 8)
  const hover = await send('textDocument/hover', {
    textDocument: { uri },
    position: { line: 5, character: 8 },
  });
  console.log('[OK] hover on greet ->', JSON.stringify(hover && hover.contents).slice(0, 200));

  // 4. definition of "greet" called in Greeter.sayHello (line 10, col 31)
  const def = await send('textDocument/definition', {
    textDocument: { uri },
    position: { line: 10, character: 31 },
  });
  console.log('[OK] definition ->', JSON.stringify(def).slice(0, 200));

  // 5. documentSymbol
  const symbols = await send('textDocument/documentSymbol', {
    textDocument: { uri },
  });
  const names = symbols ? symbols.map((s) => s.name).filter(Boolean) : [];
  console.log('[OK] documentSymbol ->', JSON.stringify(names));

  // 6. workspace/symbol
  const wsSymbols = await send('workspace/symbol', { query: 'greet' });
  const wsCount = Array.isArray(wsSymbols) ? wsSymbols.length : 0;
  console.log('[OK] workspace/symbol(greet) ->', wsCount);

  // 7. shutdown + exit
  await send('shutdown', null);
  sendNotify('exit', null);
  console.log('\n[OK] shutdown -> exit');
  console.log('\n== ALL CHECKS PASSED ==');
  process.exit(0);
}

main().catch((e) => {
  console.error('\n[FAIL]', e.message);
  child.kill();
  process.exit(1);
});