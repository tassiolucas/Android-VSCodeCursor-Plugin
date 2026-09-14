import * as path from 'path';
import * as os from 'os';
import * as childProcess from 'child_process';
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
 * Serves decompiled source for `ase-lib:` URIs (go-to-definition into
 * external libraries). Asks the LSP server to decompile the .class on demand.
 */
class AseLibContentProvider implements vscode.TextDocumentContentProvider {
    onDidChangeEmitter = new vscode.EventEmitter<vscode.Uri>();
    onDidChange = this.onDidChangeEmitter.event;

    async provideTextDocumentContent(uri: vscode.Uri): Promise<string> {
        if (!client) return '// ASE LSP not running.';
        const raw = decodeURIComponent(uri.toString().replace(/^ase-lib:/, ''));
        const bang = raw.indexOf('!');
        if (bang === -1) return `// Invalid ase-lib URI: ${raw}`;
        const jarPath = raw.slice(0, bang);
        const className = raw.slice(bang + 1).split('#')[0];

        const source = await client.sendRequest<any>(
            'workspace/executeCommand',
            {
                command: 'ase.decompile',
                arguments: [{ jarPath, className }],
            }
        );
        if (!source || typeof source !== 'string') {
            return `// Decompilation failed for ${className.replace(/\//g, '.')}`;
        }
        const header = `// Decompiled from ${jarPath.replace(/\\/g, '/').split('/').pop()}\n` +
            `// Source: ${className.replace(/\//g, '.')}\n\n`;
        return header + source;
    }
}

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

/**
 * Resolves the JDK used to launch the LSP server and returns an env override
 * (JAVA_HOME) to pass to the spawned process. The launcher script prefers
 * JAVA_HOME; a VS Code instance opened from the desktop may not have `java`
 * (or the mise shims) on its PATH.
 */
function resolveJavaEnv(): { env: NodeJS.ProcessEnv } | undefined {
    const configured = vscode.workspace.getConfiguration('ase').get<string>('server.javaPath');
    if (configured && require('fs').existsSync(configured)) {
        return { env: { ...process.env, JAVA_HOME: path.dirname(path.dirname(configured)) } };
    }
    if (process.env.JAVA_HOME && require('fs').existsSync(path.join(process.env.JAVA_HOME, 'bin', 'java'))) {
        return undefined; // already set and valid
    }
    const run = (cmd: string): string | undefined => {
        try {
            return childProcess.execSync(cmd, { encoding: 'utf8', env: process.env }).trim();
        } catch {
            return undefined;
        }
    };
    // Try mise (add its shims to PATH for the detection probe).
    const shims = `${process.env.PATH}:${os.homedir()}/.local/share/mise/shims`;
    const mise = run(`PATH="${shims}" mise which java`);
    if (mise) return { env: { ...process.env, JAVA_HOME: path.dirname(path.dirname(mise)) } };
    const which = run('command -v java || which java');
    if (which) return { env: { ...process.env, JAVA_HOME: path.dirname(path.dirname(which)) } };
    return undefined;
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
    const javaEnv = resolveJavaEnv();
    if (javaEnv) {
        setStatus('$(sync~spin) ASE: locating JDK...', '#dcdcaa');
    }
    const env = (javaEnv ? javaEnv.env : process.env) as Record<string, string>;
    const serverOptions: ServerOptions = win
        ? {
              run: { command: 'cmd.exe', args: ['/c', launcher], transport: TransportKind.stdio, options: { env } },
              debug: { command: 'cmd.exe', args: ['/c', launcher], transport: TransportKind.stdio, options: { env } },
          }
        : {
              run: { command: launcher, transport: TransportKind.stdio, options: { env } },
              debug: { command: launcher, transport: TransportKind.stdio, options: { env } },
          };

    const clientOptions: LanguageClientOptions = {
        documentSelector: [
            { language: 'kotlin' },
            { language: 'java' },
        ],
        synchronize: {
            configurationSection: 'ase',
        },
        initializationOptions: {
            generatedSources: vscode.workspace.getConfiguration('ase').get<boolean>('indexing.generatedSources', true),
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

    // Virtual documents for go-to-definition into external libraries.
    const libProvider = new AseLibContentProvider();
    context.subscriptions.push(
        vscode.workspace.registerTextDocumentContentProvider('ase-lib', libProvider)
    );
    context.subscriptions.push(
        vscode.workspace.onDidOpenTextDocument((doc) => {
            if (doc.uri.scheme === 'ase-lib') {
                void vscode.languages.setTextDocumentLanguage(doc, 'java');
            }
        })
    );

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

    // Preview commands are declared in package.json (editor title buttons);
    // register stubs so invoking them doesn't error with "command not found".
    context.subscriptions.push(
        vscode.commands.registerCommand('ase.renderComposePreview', () => {
            vscode.window.showInformationMessage('ASE: Compose preview renderer chega na Fase 4.');
        })
    );
    context.subscriptions.push(
        vscode.commands.registerCommand('ase.renderLayoutPreview', () => {
            vscode.window.showInformationMessage('ASE: Layout preview renderer chega na Fase 4.');
        })
    );

    // CodeLens "N usages" click-through: resolve references via LSP and peek.
    context.subscriptions.push(
        vscode.commands.registerCommand('ase.showReferences', async (uriStr: string, pos: { line: number; character: number }) => {
            const uri = vscode.Uri.parse(uriStr);
            const position = new vscode.Position(pos.line, pos.character);
            const locations = await vscode.commands.executeCommand<vscode.Location[]>(
                'vscode.executeReferenceProvider', uri, position
            );
            if (locations && locations.length > 0) {
                await vscode.commands.executeCommand('editor.action.showReferences', uri, position, locations);
            } else {
                vscode.window.showInformationMessage('ASE: no usages found.');
            }
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