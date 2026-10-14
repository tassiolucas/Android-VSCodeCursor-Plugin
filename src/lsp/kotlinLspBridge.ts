import * as vscode from 'vscode';
import { ProjectProfile } from '../profile/projectProfile';

const KOTLIN_EXTENSION_ID = 'JetBrains.kotlin-server';
const RELOAD_DEBOUNCE_MS = 3000;

const LspCommands = {
    RELOAD_WORKSPACE: 'jetbrains.kotlin.reloadWorkspace',
    RESTART: 'jetbrains.kotlin.restartLsp',
    CLEAR_CACHES_AND_RESTART: 'jetbrains.kotlin.clearCachesAndRestartLsp',
    EXPORT_WORKSPACE: 'jetbrains.exportWorkspaceToJson',
} as const;

export class KotlinLspBridge implements vscode.Disposable {
    private readonly disposables: vscode.Disposable[] = [];
    private pendingReload: ReturnType<typeof setTimeout> | undefined;

    constructor(private readonly log: (message: string) => void) {}

    isKotlinLspInstalled(): boolean {
        return vscode.extensions.getExtension(KOTLIN_EXTENSION_ID) !== undefined;
    }

    async promptInstallIfMissing(): Promise<void> {
        if (this.isKotlinLspInstalled()) return;
        const choice = await vscode.window.showWarningMessage(
            `ASE requires "Kotlin by JetBrains" (${KOTLIN_EXTENSION_ID}).`,
            'Install',
        );
        if (choice === 'Install') {
            await vscode.commands.executeCommand('workbench.extensions.installExtension', KOTLIN_EXTENSION_ID);
        }
    }

    async ensureGradleImport(): Promise<void> {
        const intellij = vscode.workspace.getConfiguration('intellij');
        if (intellij.get<string | null>('buildTool') === 'json') {
            await intellij.update('buildTool', undefined, vscode.ConfigurationTarget.Workspace);
            this.log('Removed legacy intellij.buildTool=json; kotlin-lsp will auto-detect Gradle.');
        }
        const projects = intellij.get<Array<{ type?: string }>>('projects', []);
        if (projects.some(project => project.type === 'json')) {
            await intellij.update('projects', undefined, vscode.ConfigurationTarget.Workspace);
            this.log('Removed legacy intellij.projects json entries.');
        }
    }

    watchSyncInputs(profile: ProjectProfile): void {
        const patterns = [profile.moduleFlagsFile, '**/*.gradle.kts', 'gradle/libs.versions.toml'];
        for (const pattern of patterns) {
            const watcher = vscode.workspace.createFileSystemWatcher(new vscode.RelativePattern(profile.root, pattern));
            const schedule = (uri: vscode.Uri) => this.scheduleReload(vscode.workspace.asRelativePath(uri));
            watcher.onDidChange(schedule);
            watcher.onDidCreate(schedule);
            watcher.onDidDelete(schedule);
            this.disposables.push(watcher);
        }
    }

    scheduleReload(reason: string): void {
        if (this.pendingReload) clearTimeout(this.pendingReload);
        this.pendingReload = setTimeout(() => void this.reloadWorkspace(reason), RELOAD_DEBOUNCE_MS);
    }

    async reloadWorkspace(reason: string): Promise<boolean> {
        this.log(`Reloading kotlin-lsp workspace (${reason})`);
        return this.run(LspCommands.RELOAD_WORKSPACE);
    }

    async showDiagnosticsMenu(): Promise<void> {
        const items: Array<vscode.QuickPickItem & { command?: string }> = [
            {
                label: 'Import failed: Run/Assemble still work via Gradle',
                description: 'Select App Module + Run App — analysis is optional',
            },
            { label: 'Reload Gradle project', description: 'kotlin-lsp reimport', command: LspCommands.RELOAD_WORKSPACE },
            { label: 'Restart kotlin-lsp', command: LspCommands.RESTART },
            { label: 'Clear caches and restart kotlin-lsp', command: LspCommands.CLEAR_CACHES_AND_RESTART },
            { label: 'Export imported workspace to JSON', description: 'inspect the module graph', command: LspCommands.EXPORT_WORKSPACE },
            { label: 'Report issue to kotlin-lsp', command: 'ase.openKotlinLspIssues' },
        ];
        const picked = await vscode.window.showQuickPick(items, { placeHolder: 'ASE: kotlin-lsp diagnostics' });
        if (picked?.command) await this.run(picked.command);
    }

    dispose(): void {
        if (this.pendingReload) clearTimeout(this.pendingReload);
        this.disposables.forEach(disposable => disposable.dispose());
    }

    private async run(command: string): Promise<boolean> {
        try {
            await vscode.commands.executeCommand(command);
            return true;
        } catch (error) {
            this.log(`Command ${command} failed: ${String(error)}`);
            vscode.window.showWarningMessage(
                'ASE: kotlin-lsp import failed. Run/Assemble still work via Gradle. Use Select App Module + Run App.',
                'Diagnostics',
            ).then(choice => { if (choice === 'Diagnostics') void this.showDiagnosticsMenu(); });
            return false;
        }
    }
}
