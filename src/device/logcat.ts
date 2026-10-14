import * as vscode from 'vscode';
import { AseContext } from '../aseContext';
import { resolveModuleDir } from '../profile/projectProfile';
import { findAppPid, resolveAdbPath } from './adb';
import { findBuiltApk } from './appInfo';
import { ensureDevice } from './devicePicker';

const LOGCAT_TERMINAL = 'ASE Logcat';

const LogLevels = [
    { label: 'Verbose', level: 'V' },
    { label: 'Debug', level: 'D' },
    { label: 'Info', level: 'I' },
    { label: 'Warn', level: 'W' },
    { label: 'Error', level: 'E' },
] as const;

type LevelPick = vscode.QuickPickItem & { level: string };

export function registerLogcat(ase: AseContext): vscode.Disposable[] {
    return [vscode.commands.registerCommand('ase.logcat', () => showLogcat(ase))];
}

async function showLogcat(ase: AseContext): Promise<void> {
    const serial = await ensureDevice(ase);
    if (!serial) return;

    const levelPicked = await vscode.window.showQuickPick(toLevelItems(), {
        title: 'Logcat minimum level',
        placeHolder: 'Info',
    });
    if (!levelPicked) return;

    const applicationId = resolveApplicationId(ase);
    const pid = applicationId
        ? await findAppPid({ root: ase.root, serial, applicationId })
        : undefined;

    const adb = shellQuote(resolveAdbPath(ase.root));
    const filter = `*:${levelPicked.level}`;
    const logcatArgs = pid ? `logcat --pid=${pid} ${filter}` : `logcat ${filter}`;
    const command = `${adb} -s ${shellQuote(serial)} ${logcatArgs}`;

    let terminal = vscode.window.terminals.find(entry => entry.name === LOGCAT_TERMINAL);
    if (!terminal) {
        terminal = vscode.window.createTerminal(LOGCAT_TERMINAL);
    }
    terminal.show();
    terminal.sendText(command, true);
    ase.log(pid ? `Logcat pid=${pid} level=${levelPicked.level}` : `Logcat (no pid) level=${levelPicked.level}`);
}

function resolveApplicationId(ase: AseContext): string | undefined {
    const appModule = ase.appModule;
    if (!appModule) return undefined;
    const moduleDir = resolveModuleDir(ase.profile, appModule);
    return findBuiltApk({ appModuleDir: moduleDir, variant: ase.variant })?.applicationId;
}

function toLevelItems(): LevelPick[] {
    return LogLevels.map(entry => ({
        label: entry.label,
        description: entry.level,
        level: entry.level,
    }));
}

function shellQuote(value: string): string {
    if (process.platform === 'win32') {
        return `"${value.replace(/"/g, '""')}"`;
    }
    return `'${value.replace(/'/g, `'\\''`)}'`;
}
