import * as vscode from 'vscode';
import { AseContext } from '../aseContext';
import { findAppPid, runAdb } from '../device/adb';
import { findBuiltApk } from '../device/appInfo';
import { ensureDevice } from '../device/devicePicker';
import { resolveModuleDir } from '../profile/projectProfile';
import { installAndLaunch } from '../run/runApp';

const JAVA_DEBUG_EXTENSION = 'vscjava.vscode-java-debug';
const JAVA_EXTENSION_PACK = 'vscjava.vscode-java-pack';
const PID_RETRY_MS = 1500;

export function registerDebug(ase: AseContext): vscode.Disposable[] {
    return [vscode.commands.registerCommand('ase.debugApp', () => debugApp(ase))];
}

async function debugApp(ase: AseContext): Promise<void> {
    ase.log('Debug: starting attach flow.');
    const context = await resolveLaunchContext(ase);
    if (!context) return;
    await attachToRunningApp(ase, context.serial, context.applicationId);
}

async function resolveLaunchContext(
    ase: AseContext,
): Promise<{ serial: string; applicationId: string } | undefined> {
    const launched = await installAndLaunch(ase);
    if (launched) {
        ase.log('Debug: install and launch succeeded.');
        return launched;
    }
    ase.log('Debug: install/launch failed; attempting attach to a running process.');
    const serial = await ensureDevice(ase);
    if (!serial) {
        ase.log('Debug: no device available.');
        return undefined;
    }
    const applicationId = resolveApplicationId(ase);
    if (!applicationId) return undefined;
    return { serial, applicationId };
}

function resolveApplicationId(ase: AseContext): string | undefined {
    const appModule = ase.appModule;
    if (!appModule) {
        vscode.window.showWarningMessage('ASE: select an app module before debugging.');
        return undefined;
    }
    const built = findBuiltApk({
        appModuleDir: resolveModuleDir(ase.profile, appModule),
        variant: ase.variant,
    });
    if (!built?.applicationId) {
        vscode.window.showWarningMessage('ASE: no built APK found for the selected variant; run assemble first.');
        return undefined;
    }
    return built.applicationId;
}

async function attachToRunningApp(ase: AseContext, serial: string, applicationId: string): Promise<void> {
    if (!(await ensureJavaDebugger(ase))) return;

    const port = vscode.workspace.getConfiguration('ase').get<number>('debug.port', 8700);
    ase.log(`Debug: looking up PID for ${applicationId}.`);
    let pid = await findAppPid({ root: ase.root, serial, applicationId });
    if (!pid) {
        await sleep(PID_RETRY_MS);
        pid = await findAppPid({ root: ase.root, serial, applicationId });
    }
    if (!pid) {
        ase.log('Debug: process not running.');
        vscode.window.showWarningMessage(`ASE: ${applicationId} is not running on the device.`);
        return;
    }

    ase.log(`Debug: adb forward tcp:${port} jdwp:${pid}.`);
    const forward = await runAdb({
        root: ase.root,
        serial,
        args: ['forward', `tcp:${port}`, `jdwp:${pid}`],
    });
    if (!forward.ok) {
        ase.log(`Debug: adb forward failed: ${forward.stderr.trim()}`);
        vscode.window.showWarningMessage('ASE: adb port forward failed.');
        return;
    }

    ase.log(`Debug: starting Java attach debugger on localhost:${port}.`);
    const started = await vscode.debug.startDebugging(undefined, {
        type: 'java',
        request: 'attach',
        name: `ASE Attach (${applicationId})`,
        hostName: 'localhost',
        port,
    });
    if (!started) {
        ase.log('Debug: vscode.debug.startDebugging returned false.');
        vscode.window.showWarningMessage('ASE: could not start the Java debugger session.');
    }
}

async function ensureJavaDebugger(ase: AseContext): Promise<boolean> {
    const extension = vscode.extensions.getExtension(JAVA_DEBUG_EXTENSION);
    if (extension) {
        if (!extension.isActive) await extension.activate();
        return true;
    }
    ase.log('Debug: Java Debugger extension not installed.');
    const action = await vscode.window.showWarningMessage(
        'ASE: Java Debugger (Extension Pack for Java) is required to attach to the app.',
        'Install Extension Pack for Java',
    );
    if (action === 'Install Extension Pack for Java') {
        await vscode.commands.executeCommand('workbench.extensions.installExtension', JAVA_EXTENSION_PACK);
    }
    return false;
}

function sleep(ms: number): Promise<void> {
    return new Promise(resolve => setTimeout(resolve, ms));
}
