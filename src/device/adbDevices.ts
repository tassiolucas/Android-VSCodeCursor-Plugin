export interface AdbDevice {
    serial: string;
    state: string;
    model?: string;
}

export function parseDevices(output: string): AdbDevice[] {
    return output
        .split(/\r?\n/)
        .slice(1)
        .map(line => line.trim())
        .filter(Boolean)
        .map(line => {
            const [serial, state, ...details] = line.split(/\s+/);
            const model = details.find(detail => detail.startsWith('model:'))?.substring('model:'.length);
            return { serial, state, model };
        });
}
