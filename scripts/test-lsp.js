#!/usr/bin/env node
/**
 * Smoke test for the ASE LSP server.
 *
 * Spawns the Kotlin server, sends real LSP messages over stdio and verifies
 * the Android-Studio-parity feature surface:
 *   initialize -> hover -> definition (+cross-module, +resources)
 *   -> signatureHelp -> documentSymbol (hierarchy) -> semanticTokens
 *   -> documentHighlight -> implementation -> typeDefinition
 *   -> prepareRename -> references -> callHierarchy -> typeHierarchy
 *   -> codeLens -> inlayHint -> workspace/symbol (+camelCase) -> shutdown
 *
 * Workspace under test:
 *   - ASE_TEST_WORKSPACE env var, if set (absolute path to a real project), OR
 *   - <repo>/test-workspace fixture (2 modules, Kotlin+Java+res).
 *
 * Usage: node test-lsp.js
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
    }, 30000);
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

child.stderr.on('data', () => {}); // server logs are noisy; keep stdout protocol clean

/** Position of `needle` (plus offset) inside `text`. */
function posOf(text, needle, offsetInNeedle = 0) {
  const idx = text.indexOf(needle);
  if (idx === -1) throw new Error(`needle not found: ${needle}`);
  const upto = text.slice(0, idx + offsetInNeedle);
  const lines = upto.split('\n');
  return { line: lines.length - 1, character: lines[lines.length - 1].length };
}

function flattenSymbols(symbols) {
  const out = [];
  for (const s of symbols || []) {
    out.push(s);
    out.push(...flattenSymbols(s.children || []));
  }
  return out;
}

async function main() {
  console.log('== ASE LSP Server smoke test ==\n');

  const workspace = process.env.ASE_TEST_WORKSPACE
    ? process.env.ASE_TEST_WORKSPACE
    : path.join(__dirname, '..', 'test-workspace');
  if (!fs.existsSync(workspace)) {
    throw new Error(`Workspace not found: ${workspace} (set ASE_TEST_WORKSPACE)`);
  }
  console.log(`Workspace: ${workspace}`);

  const usingFixture = !process.env.ASE_TEST_WORKSPACE;
  const rootUri = pathToFileURL(workspace).href;

  const coreFile = path.join(workspace, 'core/src/main/kotlin/com/example/core/Greeter.kt');
  const appFile = path.join(workspace, 'app/src/main/kotlin/com/example/app/Main.kt');
  const hasFixtureFiles = fs.existsSync(coreFile) && fs.existsSync(appFile);

  // 1. initialize
  const initResult = await send('initialize', {
    processId: process.pid,
    rootUri,
    capabilities: {},
  });
  const caps = initResult.capabilities;
  console.log('[OK] initialize');
  for (const cap of [
    'hoverProvider', 'definitionProvider', 'documentSymbolProvider',
    'workspaceSymbolProvider', 'referencesProvider', 'signatureHelpProvider',
    'documentHighlightProvider', 'implementationProvider', 'typeDefinitionProvider',
    'renameProvider', 'callHierarchyProvider', 'typeHierarchyProvider',
    'codeLensProvider', 'inlayHintProvider', 'semanticTokensProvider',
  ]) {
    if (!caps[cap]) throw new Error(`missing capability: ${cap}`);
  }
  console.log('[OK] all 15 capabilities advertised');

  sendNotify('initialized', {});

  // Wait for background indexing to finish so workspace/symbol hits the index.
  await new Promise((r) => setTimeout(r, 5000));

  if (!hasFixtureFiles) {
    console.log('[SKIP] fixture files not found in this workspace; basic checks only');
  }

  const coreText = hasFixtureFiles ? fs.readFileSync(coreFile, 'utf8') : null;
  const appText = hasFixtureFiles ? fs.readFileSync(appFile, 'utf8') : null;
  const coreUri = hasFixtureFiles ? pathToFileURL(coreFile).href : null;
  const appUri = hasFixtureFiles ? pathToFileURL(appFile).href : null;

  if (hasFixtureFiles) {
    sendNotify('textDocument/didOpen', { textDocument: { uri: coreUri, languageId: 'kotlin', version: 1, text: coreText } });
    sendNotify('textDocument/didOpen', { textDocument: { uri: appUri, languageId: 'kotlin', version: 1, text: appText } });
    console.log('[OK] didOpen core/app');
    await new Promise((r) => setTimeout(r, 500));

    // 2. hover on declaration (with KDoc + signature)
    const hover = await send('textDocument/hover', {
      textDocument: { uri: coreUri },
      position: posOf(coreText, 'greet(', 1),
    });
    if (!hover || !JSON.stringify(hover.contents).includes('greet')) {
      throw new Error('hover on greet returned no signature');
    }
    console.log('[OK] hover -> signature present');

    // 3. cross-module definition: greet usage in app -> core/Greeter.kt
    const def = await send('textDocument/definition', {
      textDocument: { uri: appUri },
      position: posOf(appText, 'greet("App")', 2),
    });
    const defList = Array.isArray(def) ? def : [];
    if (!defList.some((d) => (d.uri || d.targetUri || '').endsWith('Greeter.kt'))) {
      throw new Error('cross-module definition of greet did not resolve to Greeter.kt: ' + JSON.stringify(def));
    }
    console.log('[OK] definition (cross-module) -> core/Greeter.kt');

    // 3b. resource definition: R.string.app_name -> strings.xml
    const resDef = await send('textDocument/definition', {
      textDocument: { uri: appUri },
      position: posOf(appText, 'app_name', 2),
    });
    const resList = Array.isArray(resDef) ? resDef : [];
    if (!resList.some((d) => (d.uri || '').endsWith('strings.xml'))) {
      throw new Error('R.string.app_name did not resolve to strings.xml: ' + JSON.stringify(resDef));
    }
    console.log('[OK] definition (R.string.app_name) -> strings.xml');

    // 4. signature help inside greet("App" — the AS Ctrl+P equivalent.
    const sigHelp = await send('textDocument/signatureHelp', {
      textDocument: { uri: appUri },
      position: posOf(appText, 'greet("App")', 'greet('.length + 1),
    });
    if (!sigHelp || !sigHelp.signatures || sigHelp.signatures.length === 0) {
      throw new Error('signatureHelp returned no signatures for greet(');
    }
    const sigLabel = sigHelp.signatures[0].label;
    if (!sigLabel.includes('name: String') || !sigLabel.includes('punctuation')) {
      throw new Error('signatureHelp label missing parameters: ' + sigLabel);
    }
    console.log('[OK] signatureHelp ->', sigLabel);

    // 5. documentSymbol hierarchy: Greeter has children render + target
    const docSymbols = await send('textDocument/documentSymbol', { textDocument: { uri: coreUri } });
    const flat = flattenSymbols(docSymbols).map((s) => s.name);
    for (const expected of ['Screen', 'greet', 'Greeter', 'render', 'target']) {
      if (!flat.includes(expected)) throw new Error(`documentSymbol missing ${expected} in ${flat}`);
    }
    const greeterSym = docSymbols.find((s) => s.name === 'Greeter');
    if (!greeterSym || !(greeterSym.children || []).length) {
      throw new Error('documentSymbol is not hierarchical (Greeter has no children)');
    }
    console.log('[OK] documentSymbol -> hierarchical ->', JSON.stringify(flat));

    // 6. documentHighlight on greet in app file (import + call site)
    const highlights = await send('textDocument/documentHighlight', {
      textDocument: { uri: appUri },
      position: posOf(appText, 'greet("App")', 2),
    });
    if (!highlights || highlights.length < 2) {
      throw new Error('documentHighlight found < 2 occurrences: ' + JSON.stringify(highlights));
    }
    console.log('[OK] documentHighlight ->', highlights.length, 'occurrences');

    // 7. implementation: Screen -> Greeter + App
    const impls = await send('textDocument/implementation', {
      textDocument: { uri: coreUri },
      position: posOf(coreText, 'interface Screen', 'interface '.length + 1),
    });
    const implList = Array.isArray(impls) ? impls : [];
    if (implList.length < 2) throw new Error('implementation(Screen) found < 2: ' + JSON.stringify(impls));
    console.log('[OK] implementation(Screen) ->', implList.length, 'implementations');

    // 8. typeDefinition: `screen: Screen` property -> Screen declaration
    const typeDef = await send('textDocument/typeDefinition', {
      textDocument: { uri: appUri },
      position: posOf(appText, 'val screen: Screen', 'val screen'.length - 1),
    });
    const typeList = Array.isArray(typeDef) ? typeDef : [];
    if (!typeList.some((d) => (d.uri || '').endsWith('Greeter.kt'))) {
      throw new Error('typeDefinition(screen) did not resolve to Screen in Greeter.kt: ' + JSON.stringify(typeDef));
    }
    console.log('[OK] typeDefinition(screen) -> Screen');

    // 9. prepareRename on greet call site
    const prep = await send('textDocument/prepareRename', {
      textDocument: { uri: appUri },
      position: posOf(appText, 'greet("App")', 2),
    });
    if (!prep) throw new Error('prepareRename returned null');
    console.log('[OK] prepareRename ->', JSON.stringify(prep));

    // 10. references for greet (>= 3: core internal + app import + app call)
    const refs = await send('textDocument/references', {
      textDocument: { uri: coreUri },
      position: posOf(coreText, 'greet(', 1),
      context: { includeDeclaration: true },
    });
    if (!refs || refs.length < 3) throw new Error('references(greet) < 3: ' + JSON.stringify(refs));
    console.log('[OK] references(greet) ->', refs.length, 'usages');

    // 11. call hierarchy: prepare on greet, then incoming calls
    const chPrep = await send('textDocument/prepareCallHierarchy', {
      textDocument: { uri: coreUri },
      position: posOf(coreText, 'greet(', 1),
    });
    if (!chPrep || chPrep.length === 0) throw new Error('prepareCallHierarchy(greet) empty');
    const incoming = await send('callHierarchy/incomingCalls', { item: chPrep[0] });
    if (!incoming || incoming.length < 2) {
      throw new Error('incomingCalls(greet) < 2: ' + JSON.stringify(incoming));
    }
    console.log('[OK] callHierarchy incoming(greet) ->', incoming.length, 'callers');

    // 12. type hierarchy: prepare on Screen, then subtypes
    const thPrep = await send('textDocument/prepareTypeHierarchy', {
      textDocument: { uri: coreUri },
      position: posOf(coreText, 'interface Screen', 'interface '.length + 1),
    });
    if (!thPrep || thPrep.length === 0) throw new Error('prepareTypeHierarchy(Screen) empty');
    const subtypes = await send('typeHierarchy/subtypes', { item: thPrep[0] });
    if (!subtypes || subtypes.length < 2) {
      throw new Error('typeHierarchy subtypes(Screen) < 2: ' + JSON.stringify(subtypes));
    }
    console.log('[OK] typeHierarchy subtypes(Screen) ->', subtypes.length);

    // 13. codeLens: stored usages badges on core/Greeter.kt
    const lenses = await send('textDocument/codeLens', { textDocument: { uri: coreUri } });
    if (!lenses || lenses.length === 0) throw new Error('codeLens returned no lenses');
    console.log('[OK] codeLens ->', lenses.length, 'lenses, e.g.', lenses[0].command && lenses[0].command.title);

    // 14. inlay hints: parameter name hints in app/Main.kt (`greet("App")` -> `name:`)
    const hints = await send('textDocument/inlayHint', {
      textDocument: { uri: appUri },
      range: { start: { line: 0, character: 0 }, end: { line: 999, character: 0 } },
    });
    const hintLabels = (hints || []).map((h) => h.label);
    if (!hintLabels.some((l) => l === 'name:')) {
      throw new Error('inlayHint missing `name:` hint: ' + JSON.stringify(hintLabels));
    }
    console.log('[OK] inlayHint ->', JSON.stringify(hintLabels));
  }

  // 15. workspace/symbol (empty query = all indexed symbols; proves cross-module indexing)
  const wsSymbols = await send('workspace/symbol', { query: '' });
  const wsCount = Array.isArray(wsSymbols) ? wsSymbols.length : 0;
  console.log('[OK] workspace/symbol(*) ->', wsCount);
  if (wsCount === 0) {
    throw new Error('workspace/symbol returned 0 results — cross-module index is empty');
  }

  if (hasFixtureFiles) {
    // 15b. camelCase search: "Lh" -> LegacyHelper (Java file indexed)
    const camel = await send('workspace/symbol', { query: 'Lh' });
    const camelNames = (camel || []).map((s) => s.name);
    if (!camelNames.includes('LegacyHelper')) {
      throw new Error('camelCase search "Lh" did not find LegacyHelper: ' + JSON.stringify(camelNames));
    }
    console.log('[OK] workspace/symbol camelCase "Lh" -> LegacyHelper (Java)');
  }

  // 16. shutdown + exit
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
