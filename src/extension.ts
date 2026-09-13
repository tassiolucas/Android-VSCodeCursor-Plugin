import * as path from 'path';
import * as vscode from 'vscode';
import {
    LanguageClient,
    LanguageClientOptions,
    ServerOptions,
    TransportKind,
} from 'vscode-languageclient/node';

let client: LanguageClient | undefined;
let statusBar: vscode.StatusBarItem | undefined;

/**
 * Locates the ASE LSP server launcher.
 *
 * During development: server/build/install/ase-lsp-server/bin/ase-lsp-server.bat
 * In a packaged extension: bundled under ./server/bin
 */
function findServerLauncher(context: vscode.ExtensionContext): string | undefined {
    const candidates = [
        path.join(context.extensionPath, 'server', 'bin', process.platform === 'win32' ? 'ase-lsp-server.bat' : 'ase-lsp-server'),
        path.join(context.extensionPath, 'server', 'build', 'install', 'ase-lsp-server', 'bin', process.platform === 'win32' ? 'ase-lsp-server.bat' : 'ase-lsp-server'),
    ];
    for (const c of candidates) {
        if (require('fs').existsSync(c)) return c;
    }
    return undefined;
}

function setStatus(text: string, color?: string): void {
    if (!statusBar) return;
    statusBar.text = text;
    statusBar.color = color ?? new vscode.ThemeColor('statusBar.foreground');
    statusBar.show();
}

function startLanguageClient(context: vscode.ExtensionContext): void {
    const launcher = findServerLauncher(context);
    if (!launcher) {
        setStatus('$(error) ASE: server not built', '#ff5555');
        vscode.window.showErrorMessage(
            'ASE: LSP server not built yet. Run "npm run build:server" in the extension root.'
        );
        return;
    }

    const win = process.platform === 'win32';
    const serverOptions: ServerOptions = win
        ? {
              run: { command: 'cmd.exe', args: ['/c', launcher], transport: TransportKind.stdio },
              debug: { command: 'cmd.exe', args: ['/c', launcher], transport: TransportKind.stdio },
          }
        : {
              run: { command: launcher, transport: TransportKind.stdio },
              debug: { command: launcher, transport: TransportKind.stdio },
          };

    const clientOptions: LanguageClientOptions = {
        documentSelector: [
            { language: 'kotlin' },
            { language: 'java' },
        ],
        synchronize: {
            configurationSection: 'ase',
        },
        outputChannel: vscode.window.createOutputChannel('ASE LSP'),
        initializationFailedHandler: (error) => {
            setStatus('$(error) ASE LSP failed', '#ff5555');
            vscode.window.showErrorMessage('ASE LSP failed to initialize: ' + (error?.message ?? String(error)));
            return false;
        },
    };

    client = new LanguageClient(
        'ase-lsp',
        'Android Studio Experience Language Server',
        serverOptions,
        clientOptions
    );

    client.onDidChangeState((e) => {
        switch (e.newState) {
            case 2: // Running
                setStatus('$(flame) ASE LSP', '#4ec9b0');
                break;
            case 3: // Starting
                setStatus('$(sync~spin) ASE LSP starting...', '#dcdcaa');
                break;
            case 1: // Stopped
                setStatus('$(circle-slash) ASE LSP stopped', '#d7ba7d');
                break;
        }
    });

    client.start();
}

export async function activate(context: vscode.ExtensionContext): Promise<void> {
    // Status bar indicator so the user can see ASE is active.
    statusBar = vscode.window.createStatusBarItem(vscode.StatusBarAlignment.Right, 100);
    setStatus('$(flame) ASE LSP', '#dcdcaa');
    context.subscriptions.push(statusBar);

    // Index project command (Phase 1 stub; real indexing lands in Phase 2).
    context.subscriptions.push(
        vscode.commands.registerCommand('ase.indexProject', async () => {
            const workspaceRoot = vscode.workspace.workspaceFolders?.[0]?.uri.fsPath;
            if (!workspaceRoot) {
                vscode.window.showInformationMessage('ASE: No workspace open.');
                return;
            }
            vscode.window.withProgress(
                { location: vscode.ProgressLocation.Notification, title: 'ASE: Indexing project...' },
                async (progress) => {
                    progress.report({ increment: 20, message: 'Resolving Gradle model (Phase 2)' });
                    await new Promise((r) => setTimeout(r, 300));
                    progress.report({ increment: 80, message: 'Done (stub — full indexing in Phase 2)' });
                    await new Promise((r) => setTimeout(r, 200));
                }
            );
        })
    );

    startLanguageClient(context);

    context.subscriptions.push({
        dispose: () => {
            if (client) {
                void client.stop();
                client = undefined;
            }
        },
    });
}

export function deactivate(): Thenable<void> | undefined {
    if (!client) return undefined;
    return client.stop();
}