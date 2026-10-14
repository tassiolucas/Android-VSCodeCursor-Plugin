import * as fs from 'fs';
import * as path from 'path';

export type ModuleFlag = 'true' | 'false' | 'auto';

export interface FlagKeyModule {
    gradlePath: string;
    relativePath: string;
}

export interface FlaggedModule extends FlagKeyModule {
    key: string;
    flag: ModuleFlag;
}

export type FlagSource = 'moduleFlagsFile' | 'defaultFlagsFile' | 'none';

export interface ResolvedFlags {
    source: FlagSource;
    modules: FlaggedModule[];
}

const VALID_FLAGS: readonly string[] = ['true', 'false', 'auto'];

export function parseFlagKey(key: string): FlagKeyModule {
    const firstSlash = key.indexOf('/');
    const dotIndex = key.indexOf('.');
    const hasExplicitGroup = dotIndex !== -1 && (firstSlash === -1 || dotIndex < firstSlash);
    const group = hasExplicitGroup ? key.substring(0, dotIndex) : undefined;
    const relativePath = hasExplicitGroup ? key.substring(dotIndex + 1) : key;
    const moduleId = relativePath.substring(relativePath.lastIndexOf('/') + 1);
    const gradlePath = group ? `:${group}:${moduleId}` : `:${moduleId}`;
    return { gradlePath, relativePath };
}

export function toFlag(raw: string): ModuleFlag | undefined {
    const normalized = raw.trim().toLowerCase();
    return VALID_FLAGS.includes(normalized) ? (normalized as ModuleFlag) : undefined;
}

export function parseFlagsContent(content: string): FlaggedModule[] {
    const modules: FlaggedModule[] = [];
    for (const line of content.split(/\r?\n/)) {
        const entry = parsePropertyLine(line);
        if (!entry) continue;
        const flag = toFlag(entry.value);
        if (!flag || !entry.key.includes('/')) continue;
        modules.push({ key: entry.key, flag, ...parseFlagKey(entry.key) });
    }
    return modules;
}

export function parsePropertyLine(line: string): { key: string; value: string } | undefined {
    const trimmed = line.trim();
    if (!trimmed || trimmed.startsWith('#') || trimmed.startsWith('!')) return undefined;
    const eqIndex = trimmed.indexOf('=');
    if (eqIndex <= 0) return undefined;
    return { key: trimmed.substring(0, eqIndex).trim(), value: trimmed.substring(eqIndex + 1).trim() };
}

export function resolveFlags(root: string, moduleFlagsFile: string, defaultFlagsFile: string): ResolvedFlags {
    const local = parseFlagsContent(readIfExists(path.join(root, moduleFlagsFile)));
    if (local.length > 0) return { source: 'moduleFlagsFile', modules: local };
    const defaults = parseFlagsContent(readIfExists(path.join(root, defaultFlagsFile)));
    if (defaults.length > 0) return { source: 'defaultFlagsFile', modules: defaults };
    return { source: 'none', modules: [] };
}

function readIfExists(file: string): string {
    return fs.existsSync(file) ? fs.readFileSync(file, 'utf8') : '';
}
