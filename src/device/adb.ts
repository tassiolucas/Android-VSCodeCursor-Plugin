import * as childProcess from 'child_process';
import * as fs from 'fs';
import * as path from 'path';
import * as vscode from 'vscode';
import { parsePropertyLine } from '../profile/moduleFlags';
import { AdbDevice, parseDevices } from './adbDevices';

export type { AdbDevice };
export { parseDevices };

export interface AdbResult {
    ok: boolean;
    stdout: string;
    stderr: string;
}

const ADB_TIMEOUT_MS = 30000;

export function resolveAdbPath(root: string): string {
    const configured = vscode.workspace.getConfiguration('ase').get<string>('adb.path', '');
    if (configured) return configured;
    const sdk = process.env.ANDROID_HOME ?? process.env.ANDROID_SDK_ROOT ?? readSdkDir(root);
    const candidate = sdk ? path.join(sdk, 'platform-tools', 'adb') : undefined;
    return candidate && fs.existsSync(candidate) ? candidate : 'adb';
}

export function runAdb(params: { root: string; serial?: string; args: string[] }): Promise<AdbResult> {
    const serialArgs = params.serial ? ['-s', params.serial] : [];
    return new Promise(resolve => {
        childProcess.execFile(
            resolveAdbPath(params.root),
            [...serialArgs, ...params.args],
            { timeout: ADB_TIMEOUT_MS },
            (error, stdout, stderr) => resolve({ ok: !error, stdout: stdout.toString(), stderr: stderr.toString() }),
        );
    });
}

export async function listDevices(root: string): Promise<AdbDevice[]> {
    const result = await runAdb({ root, args: ['devices', '-l'] });
    return result.ok ? parseDevices(result.stdout) : [];
}

export async function findAppPid(params: { root: string; serial?: string; applicationId: string }): Promise<string | undefined> {
    const result = await runAdb({ root: params.root, serial: params.serial, args: ['shell', 'pidof', params.applicationId] });
    const pid = result.stdout.trim().split(/\s+/)[0];
    return result.ok && pid ? pid : undefined;
}

function readSdkDir(root: string): string | undefined {
    const file = path.join(root, 'local.properties');
    if (!fs.existsSync(file)) return undefined;
    for (const line of fs.readFileSync(file, 'utf8').split(/\r?\n/)) {
        const entry = parsePropertyLine(line);
        if (entry?.key === 'sdk.dir') return entry.value.replace(/\\\\/g, '/');
    }
    return undefined;
}
