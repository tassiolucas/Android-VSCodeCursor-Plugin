import * as vscode from 'vscode';
import { AseContext } from '../aseContext';
import { qualifiedTask, runGradleTask, variantTaskName } from './gradleTask';

export function registerGradleTasks(ase: AseContext): vscode.Disposable[] {
    return [vscode.commands.registerCommand('ase.assemble', () => assembleSelectedVariant(ase))];
}

async function assembleSelectedVariant(ase: AseContext): Promise<void> {
    const appModule = ase.appModule;
    if (!appModule) {
        vscode.window.showWarningMessage('ASE: select an app module before building.');
        return;
    }
    const variant = ase.variant;
    const task = qualifiedTask(appModule, variantTaskName('assemble', variant));
    const label = `Assemble ${variant}`;
    ase.log(`Running ${task}`);
    const ok = await runGradleTask({ root: ase.root, tasks: [task], label });
    if (ok) {
        vscode.window.showInformationMessage(`ASE: ${label} succeeded.`);
    } else {
        vscode.window.showErrorMessage(`ASE: ${label} failed.`);
    }
}
