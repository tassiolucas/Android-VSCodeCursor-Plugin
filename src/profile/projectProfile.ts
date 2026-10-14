import * as fs from 'fs';
import * as path from 'path';
import * as vscode from 'vscode';
import { FlagSource, ModuleFlag, parseFlagsContent, resolveFlags } from './moduleFlags';

export interface ProjectProfile {
    root: string;
    moduleFlagsFile: string;
    defaultFlagsFile: string;
    alwaysIncluded: Record<string, string>;
    appModules: string[];
    variants: string[];
}

export interface ProjectModule {
    gradlePath: string;
    relativePath: string;
    dir: string;
    domain: string;
    flag?: ModuleFlag;
    alwaysIncluded: boolean;
    includedAsSource: boolean;
}

export interface ProjectModules {
    flagSource: FlagSource;
    modules: ProjectModule[];
}

export function getWorkspaceRoot(): string | undefined {
    return vscode.workspace.workspaceFolders?.[0]?.uri.fsPath;
}

export function readProfile(root: string): ProjectProfile {
    const config = vscode.workspace.getConfiguration('ase.profile');
    return {
        root,
        moduleFlagsFile: config.get<string>('moduleFlagsFile', 'local.properties'),
        defaultFlagsFile: config.get<string>('defaultFlagsFile', ''),
        alwaysIncluded: config.get<Record<string, string>>('alwaysIncluded', {}),
        appModules: config.get<string[]>('appModules', []),
        variants: config.get<string[]>('variants', ['debug']),
    };
}

export function resolveModuleDir(profile: ProjectProfile, gradlePath: string): string {
    const relativePath = profile.alwaysIncluded[gradlePath]
        ?? listProjectModules(profile).modules.find(module => module.gradlePath === gradlePath)?.relativePath
        ?? gradlePath.replace(/^:/, '').replace(/:/g, '/');
    return path.join(profile.root, relativePath);
}

export function listProjectModules(profile: ProjectProfile): ProjectModules {
    const resolved = resolveFlags(profile.root, profile.moduleFlagsFile, profile.defaultFlagsFile);
    const byGradlePath = new Map<string, ProjectModule>();

    for (const [gradlePath, relativePath] of Object.entries(profile.alwaysIncluded)) {
        byGradlePath.set(gradlePath, toProjectModule({ profile, gradlePath, relativePath, alwaysIncluded: true }));
    }

    for (const flagged of resolved.modules) {
        if (byGradlePath.has(flagged.gradlePath)) continue;
        byGradlePath.set(flagged.gradlePath, toProjectModule({
            profile,
            gradlePath: flagged.gradlePath,
            relativePath: flagged.relativePath,
            alwaysIncluded: false,
            flag: flagged.flag,
        }));
    }

    const modules = [...byGradlePath.values()]
        .filter(module => hasBuildScript(module.dir))
        .sort((a, b) => a.relativePath.localeCompare(b.relativePath));
    return { flagSource: resolved.source, modules };
}

export function listAvailableFlagModules(profile: ProjectProfile): ProjectModule[] {
    const defaults = profile.defaultFlagsFile
        ? parseFlagsContent(readIfExists(path.join(profile.root, profile.defaultFlagsFile)))
        : [];
    const current = new Map(listProjectModules(profile).modules.map(module => [module.gradlePath, module]));
    const merged = new Map<string, ProjectModule>();
    for (const module of current.values()) {
        if (!module.alwaysIncluded) merged.set(module.gradlePath, module);
    }
    for (const flagged of defaults) {
        if (merged.has(flagged.gradlePath) || current.get(flagged.gradlePath)?.alwaysIncluded) continue;
        merged.set(flagged.gradlePath, toProjectModule({
            profile,
            gradlePath: flagged.gradlePath,
            relativePath: flagged.relativePath,
            alwaysIncluded: false,
        }));
    }
    return [...merged.values()]
        .filter(module => hasBuildScript(module.dir))
        .sort((a, b) => a.relativePath.localeCompare(b.relativePath));
}

function toProjectModule(params: {
    profile: ProjectProfile;
    gradlePath: string;
    relativePath: string;
    alwaysIncluded: boolean;
    flag?: ModuleFlag;
}): ProjectModule {
    const { profile, gradlePath, relativePath, alwaysIncluded, flag } = params;
    return {
        gradlePath,
        relativePath,
        dir: path.join(profile.root, relativePath),
        domain: relativePath.includes('/') ? relativePath.split('/')[0] : relativePath,
        flag,
        alwaysIncluded,
        includedAsSource: alwaysIncluded || flag === 'true',
    };
}

function hasBuildScript(dir: string): boolean {
    return fs.existsSync(path.join(dir, 'build.gradle.kts')) || fs.existsSync(path.join(dir, 'build.gradle'));
}

function readIfExists(file: string): string {
    return fs.existsSync(file) ? fs.readFileSync(file, 'utf8') : '';
}
