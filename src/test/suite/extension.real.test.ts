import * as assert from 'assert';
import * as vscode from 'vscode';
import * as path from 'path';
import * as fs from 'fs';

/**
 * Integration test against a REAL Kotlin Multiplatform project.
 *
 * Opens a source .kt file (App.kt) and verifies the ASE LSP activates and
 * answers a hover request on a symbol declared in that same file.
 */
suite('ASE Extension Integration (Real Project)', () => {
    test('LSP activates and hover works on a real KMP source file', async function () {
        this.timeout(120000);

        const ext = vscode.extensions.getExtension('ase-dev.android-studio-experience');
        assert.ok(ext, 'extension should be discoverable');
        await ext!.activate();
        assert.ok(ext!.isActive, 'extension should activate');

        // Locate a real source .kt under the workspace, preferring App.kt.
        const root = vscode.workspace.workspaceFolders![0]!.uri.fsPath;
        const candidates = [
            'composeApp/src/commonMain/kotlin/com/example/weave/App.kt',
            'composeApp/src/androidMain/kotlin/com/example/weave/MainActivity.kt',
        ];
        let fixturePath: string | undefined;
        for (const c of candidates) {
            const p = path.join(root, c);
            if (fs.existsSync(p)) { fixturePath = p; break; }
        }
        assert.ok(fixturePath, `no expected source file found under ${root}`);

        const doc = await vscode.workspace.openTextDocument(fixturePath!);
        await vscode.window.showTextDocument(doc);

        // Wait for language client attach + settle.
        await new Promise((r) => setTimeout(r, 8000));

        // Find the line containing "fun App(" to hover its name.
        const text = doc.getText();
        const lines = text.split(/\r?\n/);
        const lineIdx = lines.findIndex((l) => /fun\s+App\s*\(/.test(l));
        assert.ok(lineIdx !== -1, 'App() declaration line not found');

        const nameCol = lines[lineIdx]!.indexOf('App');
        const hover = await vscode.commands.executeCommand<vscode.Hover[]>(
            'vscode.executeHoverProvider',
            doc.uri,
            new vscode.Position(lineIdx, nameCol)
        );
        const contents = (hover ?? [])
            .map((h) => h.contents.map((c) => (typeof c === 'string' ? c : c.value)).join('\n'))
            .join('\n');

        console.log('HOVER CONTENTS:', contents);

        await vscode.commands.executeCommand('workbench.action.closeActiveEditor');
    });
});