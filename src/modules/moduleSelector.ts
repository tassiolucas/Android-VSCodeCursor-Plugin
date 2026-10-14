import * as path from 'path';
import * as vscode from 'vscode';
import { AseContext } from '../aseContext';
import { ModuleFlag, parseFlagsContent } from '../profile/moduleFlags';
import { listAvailableFlagModules, ProjectModule, ProjectProfile } from '../profile/projectProfile';
import { applyModuleFlags, FlagChanges, readTextFile, writeTextFile } from './localPropertiesFile';

interface ModulePickItem extends vscode.QuickPickItem {
    module?: ProjectModule;
}

export function registerModuleSelector(ase: AseContext): vscode.Disposable[] {
    return [vscode.commands.registerCommand('ase.selectModules', () => selectModules(ase))];
}

async function selectModules(ase: AseContext): Promise<void> {
    const profile = ase.profile;
    const modules = listAvailableFlagModules(profile);
    if (modules.length === 0) {
        vscode.window.showWarningMessage('ASE: no flaggable modules found.');
        return;
    }

    const picked = await vscode.window.showQuickPick(toPickItems(modules), {
        canPickMany: true,
        matchOnDescription: true,
        title: 'Select modules to include as source',
        placeHolder: 'Checked modules are set to true; unchecked modules that were true become false',
    });
    if (!picked) return;

    const pickedPaths = new Set(picked.flatMap(item => (item.module ? [item.module.gradlePath] : [])));
    const changes = computeChanges({ profile, modules, pickedPaths });
    if (changes.size === 0) {
        vscode.window.showInformationMessage('ASE: module selection unchanged.');
        return;
    }

    const flagsFile = path.join(profile.root, profile.moduleFlagsFile);
    const nextContent = applyModuleFlags(seedFlagsContent(profile), changes);
    writeTextFile(flagsFile, nextContent);
    ase.log(`Updated ${changes.size} module flag(s) in ${profile.moduleFlagsFile}`);
    ase.fireModulesChanged();
    vscode.window.showInformationMessage(`ASE: ${changes.size} module flag(s) updated. kotlin-lsp will reload the project.`);
}

function seedFlagsContent(profile: ProjectProfile): string {
    const flagsFile = path.join(profile.root, profile.moduleFlagsFile);
    const current = readTextFile(flagsFile);
    if (parseFlagsContent(current).length > 0) return current;
    if (!profile.defaultFlagsFile) return current;
    const defaults = parseFlagsContent(readTextFile(path.join(profile.root, profile.defaultFlagsFile)));
    if (defaults.length === 0) return current;
    const seed = new Map(defaults.map(entry => [entry.key, entry.flag] as const));
    return applyModuleFlags(current, seed);
}

function toPickItems(modules: ProjectModule[]): ModulePickItem[] {
    const items: ModulePickItem[] = [];
    let currentDomain: string | undefined;
    for (const module of modules) {
        if (module.domain !== currentDomain) {
            currentDomain = module.domain;
            items.push({ label: module.domain, kind: vscode.QuickPickItemKind.Separator });
        }
        items.push({
            label: moduleId(module),
            description: module.gradlePath,
            detail: `Current flag: ${module.flag ?? 'not set'}`,
            picked: module.flag === 'true',
            module,
        });
    }
    return items;
}

function computeChanges(params: {
    profile: ProjectProfile;
    modules: ProjectModule[];
    pickedPaths: Set<string>;
}): FlagChanges {
    const { profile, modules, pickedPaths } = params;
    const knownKeys = readKnownKeys(profile);
    const changes: FlagChanges = new Map();
    for (const module of modules) {
        const next = nextFlag(module, pickedPaths.has(module.gradlePath));
        if (!next) continue;
        changes.set(knownKeys.get(module.gradlePath) ?? deriveFlagKey(module), next);
    }
    return changes;
}

function nextFlag(module: ProjectModule, picked: boolean): ModuleFlag | undefined {
    if (picked) return module.flag === 'true' ? undefined : 'true';
    return module.flag === 'true' ? 'false' : undefined;
}

function readKnownKeys(profile: ProjectProfile): Map<string, string> {
    const files = [profile.defaultFlagsFile, profile.moduleFlagsFile].filter(file => file.length > 0);
    const keys = new Map<string, string>();
    for (const file of files) {
        for (const flagged of parseFlagsContent(readTextFile(path.join(profile.root, file)))) {
            keys.set(flagged.gradlePath, flagged.key);
        }
    }
    return keys;
}

function deriveFlagKey(module: ProjectModule): string {
    return `${module.relativePath.split('/')[0]}.${module.relativePath}`;
}

function moduleId(module: ProjectModule): string {
    return module.relativePath.substring(module.relativePath.lastIndexOf('/') + 1);
}
