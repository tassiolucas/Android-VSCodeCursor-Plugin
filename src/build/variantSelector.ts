import * as vscode from 'vscode';
import { AseContext } from '../aseContext';

export function registerVariantSelector(ase: AseContext): vscode.Disposable[] {
    const status = vscode.window.createStatusBarItem(vscode.StatusBarAlignment.Left, 50);
    const refresh = (): void => {
        const moduleLabel = ase.appModule ?? 'app';
        status.text = `$(package) ${moduleLabel} · ${ase.variant}`;
        status.tooltip = 'Build variant and app module (ASE)';
        status.command = 'ase.selectVariant';
        status.show();
    };
    refresh();
    return [
        status,
        ase.onDidChangeSelection(refresh),
        vscode.commands.registerCommand('ase.selectVariant', () => pickVariant(ase)),
        vscode.commands.registerCommand('ase.selectAppModule', () => pickAppModule(ase)),
    ];
}

async function pickVariant(ase: AseContext): Promise<void> {
    const variants = ase.profile.variants;
    if (variants.length === 0) {
        vscode.window.showWarningMessage('ASE: no build variants configured (ase.profile.variants).');
        return;
    }
    const picked = await vscode.window.showQuickPick(variants, {
        title: 'Select build variant',
        placeHolder: ase.variant,
    });
    if (!picked || picked === ase.variant) return;
    await ase.setVariant(picked);
    ase.log(`Selected variant: ${picked}`);
}

async function pickAppModule(ase: AseContext): Promise<void> {
    const modules = ase.profile.appModules;
    if (modules.length === 0) {
        vscode.window.showWarningMessage('ASE: no app modules configured (ase.profile.appModules).');
        return;
    }
    const picked = await vscode.window.showQuickPick(modules, {
        title: 'Select app module',
        placeHolder: ase.appModule,
    });
    if (!picked || picked === ase.appModule) return;
    await ase.setAppModule(picked);
    ase.log(`Selected app module: ${picked}`);
}
