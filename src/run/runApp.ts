import * as vscode from 'vscode';
import { AseContext } from '../aseContext';
import { qualifiedTask, runGradleTask, variantTaskName } from '../build/gradleTask';
import { runAdb } from '../device/adb';
import { findBuiltApk } from '../device/appInfo';
import { ensureDevice } from '../device/devicePicker';
import { resolveModuleDir } from '../profile/projectProfile';

export function registerRunApp(ase: AseContext): vscode.Disposable[] {
    return [vscode.commands.registerCommand('ase.runApp', () => installAndLaunch(ase))];
}

export async function installAndLaunch(
    ase: AseContext,
): Promise<{ serial: string; applicationId: string } | undefined> {
    const serial = await ensureDevice(ase);
    if (!serial) return undefined;

    const appModule = ase.appModule;
    if (!appModule) {
        vscode.window.showWarningMessage('ASE: select an app module before running.');
        return undefined;
    }

    const variant = ase.variant;
    const moduleDir = resolveModuleDir(ase.profile, appModule);
    const task = qualifiedTask(appModule, variantTaskName('install', variant));
    const label = `Install ${variant}`;
    ase.log(`Running ${task} on ${serial}`);
    const installed = await runGradleTask({ root: ase.root, tasks: [task], label });
    if (!installed) {
        vscode.window.showErrorMessage(`ASE: ${label} failed.`);
        return undefined;
    }

    const built = findBuiltApk({ appModuleDir: moduleDir, variant });
    const applicationId = built?.applicationId;
    if (!applicationId) {
        vscode.window.showWarningMessage('ASE: could not resolve applicationId from build outputs.');
        return undefined;
    }

    const launch = await runAdb({
        root: ase.root,
        serial,
        args: ['shell', 'monkey', '-p', applicationId, '-c', 'android.intent.category.LAUNCHER', '1'],
    });
    if (!launch.ok) {
        vscode.window.showErrorMessage('ASE: app installed but launch failed.');
        return undefined;
    }

    ase.log(`Launched ${applicationId} on ${serial}`);
    vscode.window.showInformationMessage(`ASE: running ${applicationId} on ${serial}.`);
    return { serial, applicationId };
}
