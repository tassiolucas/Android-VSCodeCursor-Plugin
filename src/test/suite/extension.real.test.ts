import * as assert from 'assert';
import * as vscode from 'vscode';
import * as path from 'path';
import * as fs from 'fs';

/**
 * Integration test against a REAL Kotlin Multiplatform project.
 *
 * Opens a source .kt file from the workspace and verifies the ASE LSP
 * activates and answers a hover request on a declaration.
 *
 * The test is workspace-agnostic: it scans the workspace root (ASE_TEST_WORKSPACE
 * env var, or the repo's test-workspace fixture) for any .kt file and hovers a
 * declaration the structural analyzer can answer.
 */
suite('ASE Extension Integration (Real Project)', () => {
    test('LSP activates and hover works on a real KMP source file', async function () {
        this.timeout(180000);

        const ext = vscode.extensions.getExtension('ase-dev.android-studio-experience');
        assert.ok(ext, 'extension should be discoverable');
        await ext!.activate();
        assert.ok(ext!.isActive, 'extension should activate');

        const root = vscode.workspace.workspaceFolders![0]!.uri.fsPath;

        // Find the first .kt file with a declaration the analyzer can answer.
        const declPatterns = [
            ['fun ', /fun\s+(\w+)/],
            ['class ', /class\s+(\w+)/],
            ['interface ', /interface\s+(\w+)/],
            ['object ', /object\s+(\w+)/],
            ['enum class ', /enum class\s+(\w+)/],
            ['val ', /(?:val|var)\s+(\w+)/],
            ['var ', /(?:val|var)\s+(\w+)/],
        ] as const;

        let fixturePath: string | undefined;
        let lineIdx = -1;
        let nameCol = -1;

        for (const file of walkKtFiles(root)) {
            const text = fs.readFileSync(file, 'utf8');
            const lines = text.split(/\r?\n/);
            let hit = false;
            for (let i = 0; i < lines.length; i++) {
                const trimmed = lines[i].trim();
                const prefix = declPatterns.find(([p]) => trimmed.startsWith(p));
                if (!prefix) continue;
                const m = prefix[1].exec(trimmed);
                if (!m || !m[1]) continue;
                const indent = lines[i].indexOf(trimmed.charAt(0));
                lineIdx = i;
                nameCol = indent + m.index + m[0].indexOf(m[1]);
                fixturePath = file;
                hit = true;
                break;
            }
            if (hit) break;
        }

        assert.ok(fixturePath, `no analyzable .kt declaration found under ${root}`);
        assert.ok(lineIdx !== -1 && nameCol !== -1, 'declaration position not found');

        const doc = await vscode.workspace.openTextDocument(fixturePath!);
        await vscode.window.showTextDocument(doc);

        // Wait for language client attach + settle.
        await new Promise((r) => setTimeout(r, 8000));

        const hover = await vscode.commands.executeCommand<vscode.Hover[]>(
            'vscode.executeHoverProvider',
            doc.uri,
            new vscode.Position(lineIdx, nameCol)
        );
        const contents = (hover ?? [])
            .map((h) => h.contents.map((c) => (typeof c === 'string' ? c : c.value)).join('\n'))
            .join('\n');

        console.log('HOVER CONTENTS:', contents);
        assert.ok(contents.length > 0, 'hover should return contents for a declaration');

        await vscode.commands.executeCommand('workbench.action.closeActiveEditor');
    });
    test('go-to-definition into external library opens decompiled document', async function () {
        this.timeout(240000);

        const ext = vscode.extensions.getExtension('ase-dev.android-studio-experience');
        await ext!.activate();

        const root = vscode.workspace.workspaceFolders![0]!.uri.fsPath;
        if (root.endsWith('test-workspace')) {
            // The fixture has no external dependencies; library navigation is
            // covered by scripts/test-libraries.js against a real project.
            console.log('SKIP: fixture workspace has no external libs');
            return;
        }
        const usage = findLibraryUsage(root);
        if (!usage) {
            console.log('SKIP: no external library usage found under workspace');
            return;
        }

        const doc = await vscode.workspace.openTextDocument(usage.filePath);
        await vscode.window.showTextDocument(doc);

        // Poll definition until background library indexing resolves to ase-lib:.
        let aseLibUri: vscode.Uri | undefined;
        for (let attempt = 0; attempt < 60; attempt++) {
            const defs = await vscode.commands.executeCommand<vscode.Location[]>(
                'vscode.executeDefinitionProvider',
                doc.uri,
                new vscode.Position(usage.line, usage.character)
            );
            const hit = (defs ?? []).find((l) => l.uri.scheme === 'ase-lib');
            if (hit) { aseLibUri = hit.uri; break; }
            await new Promise((r) => setTimeout(r, 3000));
        }
        assert.ok(aseLibUri, 'definition never resolved to ase-lib: URI');

        const virtual = await vscode.workspace.openTextDocument(aseLibUri!);
        const text = virtual.getText();
        console.log('DECOMPILED LENGTH:', text.length);
        console.log('DECOMPILED HEAD:', text.slice(0, 200).replace(/\n/g, ' '));
        assert.ok(text.length > 100, 'decompiled document should have real source content');
        assert.ok(text.includes(usage.simple), 'decompiled source should mention the class');

        await vscode.commands.executeCommand('workbench.action.closeActiveEditor');
        await vscode.commands.executeCommand('workbench.action.closeActiveEditor');
    });
});

interface LibUsage { filePath: string; line: number; character: number; simple: string; }

function findLibraryUsage(root: string): LibUsage | undefined {
    for (const file of walkKtFiles(root)) {
        const text = fs.readFileSync(file, 'utf8');
        const lines = text.split(/\r?\n/);
        for (let i = 0; i < lines.length; i++) {
            const m = lines[i].match(/^import\s+([\w.]+)\.([A-Z][\w]*)\s*$/);
            if (!m) continue;
            const simple = m[2];
            for (let j = 0; j < lines.length; j++) {
                if (j === i) continue;
                const um = lines[j].match(new RegExp(`\\b(${simple})\\b`));
                if (um) {
                    return { filePath: file, line: j, character: um.index!, simple };
                }
            }
        }
    }
    return undefined;
}

function walkKtFiles(root: string): string[] {
    const files: string[] = [];
    const stack = [root];
    while (stack.length) {
        const dir = stack.pop()!;
        let entries: fs.Dirent[];
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

/**
 * End-to-end navigation parity checks against the built-in test-workspace
 * fixture (2 modules: app + core, Kotlin + Java + res). Runs only when the
 * workspace under test is the fixture (it has the required structure).
 */
suite('ASE Navigation Parity (fixture)', () => {
    const fixtureRoot = path.resolve(__dirname, '..', '..', '..', 'test-workspace');
    const coreFile = path.join(fixtureRoot, 'core/src/main/kotlin/com/example/core/Greeter.kt');
    const appFile = path.join(fixtureRoot, 'app/src/main/kotlin/com/example/app/Main.kt');

    function posOf(text: string, needle: string, off = 0): vscode.Position {
        const idx = text.indexOf(needle);
        assert.ok(idx >= 0, `needle not found: ${needle}`);
        const upto = text.slice(0, idx + off);
        const lines = upto.split('\n');
        return new vscode.Position(lines.length - 1, lines[lines.length - 1].length);
    }

    async function openAndWait(file: string): Promise<vscode.TextDocument> {
        const doc = await vscode.workspace.openTextDocument(file);
        await vscode.window.showTextDocument(doc);
        return doc;
    }

    async function waitForIndex(): Promise<void> {
        for (let i = 0; i < 40; i++) {
            const syms = await vscode.commands.executeCommand<vscode.SymbolInformation[]>(
                'vscode.executeWorkspaceSymbolProvider', ''
            );
            if ((syms ?? []).length > 0) return;
            await new Promise((r) => setTimeout(r, 1500));
        }
        assert.fail('index never became non-empty');
    }

    test('full navigation feature set works in the real client', async function () {
        this.timeout(240000);

        if (!vscode.workspace.workspaceFolders?.[0]?.uri.fsPath.endsWith('test-workspace')) {
            this.skip();
        }

        const ext = vscode.extensions.getExtension('ase-dev.android-studio-experience');
        await ext!.activate();

        await waitForIndex();
        const appDoc = await openAndWait(appFile);
        const coreDoc = await openAndWait(coreFile);
        const appText = appDoc.getText();
        const coreText = coreDoc.getText();
        await new Promise((r) => setTimeout(r, 1500));

        // 1. Signature help (Ctrl+P equivalent) inside greet("App")
        const sigHelp = await vscode.commands.executeCommand<vscode.SignatureHelp>(
            'vscode.executeSignatureHelpProvider',
            appDoc.uri,
            posOf(appText, 'greet("App")', 'greet('.length + 1)
        );
        assert.ok(sigHelp && sigHelp.signatures.length > 0, 'signatureHelp should return signatures');
        assert.ok(
            sigHelp!.signatures[0]!.label.includes('name: String'),
            'signature should include parameters: ' + sigHelp!.signatures[0]!.label
        );

        // 2. Cross-module definition: greet in app -> core/Greeter.kt
        const defs = await vscode.commands.executeCommand<vscode.Location[]>(
            'vscode.executeDefinitionProvider',
            appDoc.uri,
            posOf(appText, 'greet("App")', 2)
        );
        assert.ok((defs ?? []).some((l) => l.uri.fsPath.endsWith('Greeter.kt')),
            'greet should resolve cross-module to Greeter.kt');

        // 3. Resource navigation: R.string.app_name -> strings.xml
        const resDefs = await vscode.commands.executeCommand<vscode.Location[]>(
            'vscode.executeDefinitionProvider',
            appDoc.uri,
            posOf(appText, 'app_name', 2)
        );
        assert.ok((resDefs ?? []).some((l) => l.uri.fsPath.endsWith('strings.xml')),
            'R.string.app_name should resolve to strings.xml');

        // 4. Implementations of Screen -> Greeter + App
        const impls = await vscode.commands.executeCommand<vscode.Location[]>(
            'vscode.executeImplementationProvider',
            coreDoc.uri,
            posOf(coreText, 'interface Screen', 'interface '.length + 1)
        );
        assert.ok((impls ?? []).length >= 2, 'Screen should have >= 2 implementations, got ' + (impls ?? []).length);

        // 5. Type definition: screen: Screen -> Screen
        const typeDefs = await vscode.commands.executeCommand<vscode.Location[]>(
            'vscode.executeTypeDefinitionProvider',
            appDoc.uri,
            posOf(appText, 'val screen: Screen', 'val screen'.length - 1)
        );
        assert.ok((typeDefs ?? []).some((l) => l.uri.fsPath.endsWith('Greeter.kt')),
            'type of screen should resolve to Screen in Greeter.kt');

        // 6. References on a CLASS (DataRepository regression: class-name usages)
        const classRefs = await vscode.commands.executeCommand<vscode.Location[]>(
            'vscode.executeReferenceProvider',
            coreDoc.uri,
            posOf(coreText, 'class Greeter', 'class '.length + 1)
        );
        assert.ok((classRefs ?? []).length >= 2, 'Greeter should have usages (app module), got ' + (classRefs ?? []).length);

        // 7. Workspace symbol camelCase: "Lh" -> LegacyHelper (Java file)
        const camel = await vscode.commands.executeCommand<vscode.SymbolInformation[]>(
            'vscode.executeWorkspaceSymbolProvider', 'Lh'
        );
        const camelNames = (camel ?? []).map((s) => s.name);
        assert.ok(camelNames.includes('LegacyHelper'),
            'camelCase "Lh" should find LegacyHelper, got ' + JSON.stringify(camelNames));

        // 8. Document symbols are hierarchical (Greeter has children)
        const docSymbols = await vscode.commands.executeCommand<vscode.DocumentSymbol[]>(
            'vscode.executeDocumentSymbolProvider', coreDoc.uri
        );
        const greeter = (docSymbols ?? []).find((s) => s.name === 'Greeter');
        assert.ok(greeter && greeter.children.length > 0, 'documentSymbol should be hierarchical');

        await vscode.commands.executeCommand('workbench.action.closeAllEditors');
    });
});
