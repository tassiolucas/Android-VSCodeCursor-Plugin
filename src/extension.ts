import * as vscode from 'vscode';
import { AseContext } from './aseContext';
import { getWorkspaceRoot } from './profile/projectProfile';
import { registerModuleSelector } from './modules/moduleSelector';
import { registerAndroidTree } from './tree/androidProjectTree';
import { registerVariantSelector } from './build/variantSelector';
import { registerGradleTasks } from './build/gradleTasks';
import { registerDevicePicker } from './device/devicePicker';
import { registerLogcat } from './device/logcat';
import { registerRunApp } from './run/runApp';
import { registerDebug } from './debug/attachDebugger';
import { registerComposePreview } from './preview/composePreview';

const KOTLIN_LSP_ISSUES_URL = 'https://github.com/Kotlin/kotlin-lsp/issues/new';

export async function activate(context: vscode.ExtensionContext): Promise<void> {
    const root = getWorkspaceRoot();
    if (!root) return;

    const ase = new AseContext(root, context);
    context.subscriptions.push(ase);

    context.subscriptions.push(
        vscode.commands.registerCommand('ase.reloadProject', () => ase.bridge.reloadWorkspace('manual')),
        vscode.commands.registerCommand('ase.lspDiagnostics', () => ase.bridge.showDiagnosticsMenu()),
        vscode.commands.registerCommand('ase.openKotlinLspIssues', () =>
            vscode.env.openExternal(vscode.Uri.parse(KOTLIN_LSP_ISSUES_URL))),
        ...registerModuleSelector(ase),
        ...registerAndroidTree(ase),
        ...registerVariantSelector(ase),
        ...registerGradleTasks(ase),
        ...registerDevicePicker(ase),
        ...registerLogcat(ase),
        ...registerRunApp(ase),
        ...registerDebug(ase),
        ...registerComposePreview(ase),
    );

    await ase.bridge.promptInstallIfMissing();
    await ase.bridge.ensureGradleImport();
    if (vscode.workspace.getConfiguration('ase').get<boolean>('autoReload', true)) {
        ase.bridge.watchSyncInputs(ase.profile);
    }
}

export function deactivate(): void {}
