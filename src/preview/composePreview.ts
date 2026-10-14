import * as vscode from 'vscode';
import { AseContext } from '../aseContext';
import { findAppPid, runAdb } from '../device/adb';
import { findBuiltApk } from '../device/appInfo';
import { ensureDevice } from '../device/devicePicker';
import { resolveModuleDir } from '../profile/projectProfile';
import { installAndLaunch } from '../run/runApp';

const PREVIEW_ACTIVITY = 'androidx.compose.ui.tooling.PreviewActivity';
const PREVIEW_SCAN_LINES = 8;

interface RunPreviewArg {
    fqName?: string;
}

export function registerComposePreview(ase: AseContext): vscode.Disposable[] {
    const provider = new ComposePreviewCodeLensProvider();
    return [
        vscode.commands.registerCommand('ase.runComposePreview', (arg?: RunPreviewArg) =>
            runComposePreview(ase, arg)),
        vscode.languages.registerCodeLensProvider({ language: 'kotlin', scheme: 'file' }, provider),
    ];
}

class ComposePreviewCodeLensProvider implements vscode.CodeLensProvider {
    provideCodeLenses(document: vscode.TextDocument): vscode.CodeLens[] {
        const pkg = readPackageName(document.getText());
        return findPreviewTargets(document).map(target => {
            const fqName = pkg ? `${pkg}.${target.name}` : target.name;
            const range = new vscode.Range(target.line, 0, target.line, 0);
            const command: vscode.Command = {
                title: 'Run Compose Preview on Device',
                command: 'ase.runComposePreview',
                arguments: [{ fqName }],
            };
            return new vscode.CodeLens(range, command);
        });
    }
}

async function runComposePreview(ase: AseContext, arg?: RunPreviewArg): Promise<void> {
    const fqName = arg?.fqName ?? fqNameFromActiveEditor();
    if (!fqName) {
        vscode.window.showWarningMessage('ASE: place the cursor on a @Preview composable or invoke from CodeLens.');
        return;
    }
    ase.log(`Preview: launching ${fqName}.`);
    const context = await resolveAppForPreview(ase);
    if (!context) return;

    const component = `${context.applicationId}/${PREVIEW_ACTIVITY}`;
    const result = await runAdb({
        root: ase.root,
        serial: context.serial,
        args: ['shell', 'am', 'start', '-n', component, '-e', 'composable', fqName],
    });
    if (!result.ok) {
        ase.log(`Preview: am start failed: ${result.stderr.trim()}`);
        vscode.window.showWarningMessage('ASE: failed to start Compose PreviewActivity on device.');
        return;
    }
    ase.log(`Preview: started ${component} with composable=${fqName}.`);
}

async function resolveAppForPreview(
    ase: AseContext,
): Promise<{ serial: string; applicationId: string } | undefined> {
    const serial = await ensureDevice(ase);
    if (!serial) {
        ase.log('Preview: no device selected.');
        return undefined;
    }
    const applicationId = resolveApplicationId(ase);
    if (!applicationId) return undefined;

    const pid = await findAppPid({ root: ase.root, serial, applicationId });
    if (pid) {
        ase.log(`Preview: ${applicationId} already running (pid ${pid}).`);
        return { serial, applicationId };
    }

    ase.log('Preview: app not running; install and launch.');
    const launched = await installAndLaunch(ase);
    if (!launched) {
        ase.log('Preview: install/launch failed.');
        return undefined;
    }
    return launched;
}

function resolveApplicationId(ase: AseContext): string | undefined {
    const appModule = ase.appModule;
    if (!appModule) {
        vscode.window.showWarningMessage('ASE: select an app module before running preview.');
        return undefined;
    }
    const built = findBuiltApk({
        appModuleDir: resolveModuleDir(ase.profile, appModule),
        variant: ase.variant,
    });
    if (!built?.applicationId) {
        vscode.window.showWarningMessage('ASE: no built APK found; run assemble first.');
        return undefined;
    }
    return built.applicationId;
}

function fqNameFromActiveEditor(): string | undefined {
    const editor = vscode.window.activeTextEditor;
    if (!editor || editor.document.languageId !== 'kotlin') return undefined;
    const doc = editor.document;
    const pkg = readPackageName(doc.getText());
    if (!pkg) return undefined;
    const line = editor.selection.active.line;
    for (let i = line; i >= Math.max(0, line - PREVIEW_SCAN_LINES); i--) {
        const funMatch = doc.lineAt(i).text.match(/\bfun\s+(\w+)\s*\(/);
        if (funMatch) return `${pkg}.${funMatch[1]}`;
    }
    return undefined;
}

function readPackageName(text: string): string | undefined {
    return text.match(/^[\t ]*package\s+([\w.]+)/m)?.[1];
}

function findPreviewTargets(document: vscode.TextDocument): Array<{ name: string; line: number }> {
    const targets: Array<{ name: string; line: number }> = [];
    for (let i = 0; i < document.lineCount; i++) {
        if (!/@Preview\b/.test(document.lineAt(i).text)) continue;
        for (let j = i; j < Math.min(i + PREVIEW_SCAN_LINES, document.lineCount); j++) {
            const funMatch = document.lineAt(j).text.match(/\bfun\s+(\w+)\s*\(/);
            if (funMatch) {
                targets.push({ name: funMatch[1], line: j });
                break;
            }
        }
    }
    return targets;
}
