import * as vscode from 'vscode';
import { AseContext } from '../aseContext';
import { AdbDevice, listDevices } from './adb';

interface DevicePickItem extends vscode.QuickPickItem {
    serial: string;
}

export function registerDevicePicker(ase: AseContext): vscode.Disposable[] {
    return [vscode.commands.registerCommand('ase.selectDevice', () => selectDevice(ase))];
}

export async function ensureDevice(ase: AseContext): Promise<string | undefined> {
    const connected = await connectedDevices(ase.root);
    const stored = ase.device;
    if (stored && connected.some(device => device.serial === stored)) {
        return stored;
    }
    if (stored) {
        await ase.setDevice(undefined);
    }
    if (connected.length === 1) {
        await ase.setDevice(connected[0].serial);
        return connected[0].serial;
    }
    if (connected.length === 0) {
        vscode.window.showWarningMessage('ASE: no Android device or emulator connected.');
        return undefined;
    }
    return selectDevice(ase, connected);
}

async function selectDevice(ase: AseContext, devices?: AdbDevice[]): Promise<string | undefined> {
    const connected = devices ?? await connectedDevices(ase.root);
    if (connected.length === 0) {
        vscode.window.showWarningMessage('ASE: no Android device or emulator connected.');
        return undefined;
    }
    const picked = await vscode.window.showQuickPick(toPickItems(connected), {
        title: 'Select Android device',
        placeHolder: ase.device,
    });
    if (!picked) return undefined;
    await ase.setDevice(picked.serial);
    ase.log(`Selected device: ${picked.serial}`);
    return picked.serial;
}

async function connectedDevices(root: string): Promise<AdbDevice[]> {
    const devices = await listDevices(root);
    return devices.filter(device => device.state === 'device');
}

function toPickItems(devices: AdbDevice[]): DevicePickItem[] {
    return devices.map(device => ({
        label: device.model ?? device.serial,
        description: device.serial,
        detail: device.state,
        serial: device.serial,
    }));
}
