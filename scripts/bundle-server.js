#!/usr/bin/env node
/**
 * Bundles the freshly built LSP server into server/{bin,lib} so `vsce package`
 * ships the CURRENT build (server/build is .vscodeignore'd).
 *
 * Without this step the packaged extension silently ships whatever stale jars
 * were left in server/lib by a previous manual copy — this bit us once
 * (Phase 3.5 never reached the installed extension).
 */
const fs = require('fs');
const path = require('path');

const serverDir = path.join(__dirname, '..', 'server');
const distDir = path.join(serverDir, 'build', 'install', 'ase-lsp-server');

if (!fs.existsSync(path.join(distDir, 'bin')) || !fs.existsSync(path.join(distDir, 'lib'))) {
  console.error(`[bundle-server] distribution not found at ${distDir} — run "npm run build:server" first`);
  process.exit(1);
}

for (const dir of ['bin', 'lib']) {
  const target = path.join(serverDir, dir);
  fs.rmSync(target, { recursive: true, force: true });
  fs.cpSync(path.join(distDir, dir), target, { recursive: true });
}

// Sanity: report the bundled server jar so stale bundles are obvious in logs.
const jars = fs.readdirSync(path.join(serverDir, 'lib')).filter((f) => f.startsWith('ase-lsp-server'));
for (const jar of jars) {
  const st = fs.statSync(path.join(serverDir, 'lib', jar));
  console.log(`[bundle-server] bundled ${jar} (${st.size} bytes, mtime ${st.mtime.toISOString()})`);
}
console.log('[bundle-server] server/{bin,lib} refreshed from build/install');
