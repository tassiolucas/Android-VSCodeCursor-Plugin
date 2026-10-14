import * as fs from 'fs';
import { ModuleFlag, parsePropertyLine } from '../profile/moduleFlags';

export type FlagChanges = Map<string, ModuleFlag | undefined>;

export function applyModuleFlags(content: string, changes: FlagChanges): string {
    const eol = content.includes('\r\n') ? '\r\n' : '\n';
    const lines = content.length > 0 ? content.split(/\r?\n/) : [];
    const hadTrailingNewline = lines.length > 0 && lines[lines.length - 1] === '';
    if (hadTrailingNewline) lines.pop();

    const handled = new Set<string>();
    const updated = lines.flatMap(line => updateLine({ line, changes, handled }));
    const appended = [...changes.entries()]
        .filter(([key, flag]) => flag !== undefined && !handled.has(key))
        .map(([key, flag]) => `${key}=${flag}`);

    const result = [...updated, ...appended];
    if (result.length === 0) return '';
    const needsTrailingNewline = hadTrailingNewline || appended.length > 0;
    return result.join(eol) + (needsTrailingNewline ? eol : '');
}

export function readTextFile(file: string): string {
    return fs.existsSync(file) ? fs.readFileSync(file, 'utf8') : '';
}

export function writeTextFile(file: string, content: string): void {
    fs.writeFileSync(file, content, 'utf8');
}

function updateLine(params: { line: string; changes: FlagChanges; handled: Set<string> }): string[] {
    const { line, changes, handled } = params;
    const entry = parsePropertyLine(line);
    if (!entry || !changes.has(entry.key)) return [line];
    if (handled.has(entry.key)) return [];
    handled.add(entry.key);
    const flag = changes.get(entry.key);
    if (flag === undefined) return [];
    return [`${line.substring(0, line.indexOf('=') + 1)}${flag}`];
}
