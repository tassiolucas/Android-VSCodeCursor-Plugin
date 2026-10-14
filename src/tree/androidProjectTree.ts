import * as fs from 'fs';
import * as path from 'path';
import * as vscode from 'vscode';
import { AseContext } from '../aseContext';
import { listProjectModules, ProjectModule } from '../profile/projectProfile';

type SectionKind = 'manifests' | 'sources' | 'res';

interface TreeNode {
    id: string;
    label: string;
    resourceUri?: vscode.Uri;
    collapsible: vscode.TreeItemCollapsibleState;
    icon?: vscode.ThemeIcon;
    tooltip?: string;
    context?: SectionKind | 'domain' | 'module' | 'gradle' | 'fs';
    module?: ProjectModule;
    section?: SectionKind;
}

export function registerAndroidTree(ase: AseContext): vscode.Disposable[] {
    const provider = new AndroidProjectTreeProvider(ase);
    const flagsPattern = new vscode.RelativePattern(ase.root, ase.profile.moduleFlagsFile);
    const flagsWatcher = vscode.workspace.createFileSystemWatcher(flagsPattern);
    const refresh = () => provider.refresh();
    flagsWatcher.onDidChange(refresh);
    flagsWatcher.onDidCreate(refresh);
    flagsWatcher.onDidDelete(refresh);
    return [
        vscode.window.registerTreeDataProvider('ase.androidProject', provider),
        vscode.commands.registerCommand('ase.refreshTree', refresh),
        ase.onDidChangeModules(refresh),
        flagsWatcher,
    ];
}

class AndroidProjectTreeProvider implements vscode.TreeDataProvider<TreeNode> {
    private readonly changed = new vscode.EventEmitter<TreeNode | undefined>();
    readonly onDidChangeTreeData = this.changed.event;

    constructor(private readonly ase: AseContext) {}

    refresh(): void {
        this.changed.fire(undefined);
    }

    getTreeItem(node: TreeNode): vscode.TreeItem {
        const item = new vscode.TreeItem(node.label, node.collapsible);
        item.id = node.id;
        item.resourceUri = node.resourceUri;
        item.iconPath = node.icon;
        item.tooltip = node.tooltip;
        if (node.module && node.context === 'module') {
            const badge = moduleBadge(node.module);
            item.description = badge ? `${badge} · ${node.module.gradlePath}` : node.module.gradlePath;
        }
        if (node.resourceUri && node.collapsible === vscode.TreeItemCollapsibleState.None) {
            item.command = { command: 'vscode.open', title: 'Open', arguments: [node.resourceUri] };
        }
        return item;
    }

    getChildren(node?: TreeNode): TreeNode[] {
        if (!node) return this.rootChildren();
        if (node.context === 'domain') return this.modulesForDomain(node.label);
        if (node.context === 'module' && node.module) return this.moduleSections(node.module);
        if (node.context === 'manifests' && node.module) return this.manifestNodes(node.module);
        if (node.context === 'sources' && node.module) return this.sourceRoots(node.module);
        if (node.context === 'res' && node.resourceUri) return this.readDir(node.resourceUri.fsPath, node.id);
        if (node.context === 'gradle') return this.gradleFiles();
        if (node.context === 'fs' && node.resourceUri) return this.readDir(node.resourceUri.fsPath, node.id);
        return [];
    }

    private rootChildren(): TreeNode[] {
        const { modules } = listProjectModules(this.ase.profile);
        const domains = [...new Set(modules.map(module => module.domain))].sort((a, b) => a.localeCompare(b));
        return [
            ...domains.map(domain => this.node({
                id: `domain:${domain}`,
                label: domain,
                collapsible: vscode.TreeItemCollapsibleState.Expanded,
                icon: new vscode.ThemeIcon('folder'),
                context: 'domain',
            })),
            this.node({
                id: 'gradle-scripts',
                label: 'Gradle Scripts',
                collapsible: vscode.TreeItemCollapsibleState.Expanded,
                icon: new vscode.ThemeIcon('file-code'),
                context: 'gradle',
            }),
        ];
    }

    private modulesForDomain(domain: string): TreeNode[] {
        const { modules } = listProjectModules(this.ase.profile);
        return modules
            .filter(module => module.domain === domain)
            .map(module => this.node({
                id: `module:${module.gradlePath}`,
                label: path.basename(module.relativePath),
                resourceUri: vscode.Uri.file(module.dir),
                collapsible: vscode.TreeItemCollapsibleState.Collapsed,
                icon: new vscode.ThemeIcon('package'),
                tooltip: module.relativePath,
                context: 'module',
                module,
            }));
    }

    private moduleSections(module: ProjectModule): TreeNode[] {
        const resDir = path.join(module.dir, 'src', 'main', 'res');
        const sections: TreeNode[] = [
            this.node({
                id: `${module.gradlePath}:manifests`,
                label: 'manifests',
                collapsible: vscode.TreeItemCollapsibleState.Collapsed,
                icon: new vscode.ThemeIcon('file-submodule'),
                context: 'manifests',
                module,
            }),
            this.node({
                id: `${module.gradlePath}:sources`,
                label: 'kotlin+java',
                collapsible: vscode.TreeItemCollapsibleState.Collapsed,
                icon: new vscode.ThemeIcon('file-code'),
                context: 'sources',
                module,
            }),
        ];
        if (fs.existsSync(resDir)) {
            sections.push(this.node({
                id: `${module.gradlePath}:res`,
                label: 'res',
                resourceUri: vscode.Uri.file(resDir),
                collapsible: vscode.TreeItemCollapsibleState.Collapsed,
                icon: new vscode.ThemeIcon('folder'),
                context: 'res',
                section: 'res',
            }));
        }
        return sections;
    }

    private manifestNodes(module: ProjectModule): TreeNode[] {
        return listManifests(module.dir).map(file => {
            const name = path.relative(module.dir, file).split(path.sep).join('/');
            return this.node({
                id: `manifest:${file}`,
                label: name,
                resourceUri: vscode.Uri.file(file),
                collapsible: vscode.TreeItemCollapsibleState.None,
                icon: new vscode.ThemeIcon('file'),
                context: 'fs',
            });
        });
    }

    private sourceRoots(module: ProjectModule): TreeNode[] {
        const roots = [
            'src/main/kotlin', 'src/main/java',
            'src/commonMain/kotlin', 'src/androidMain/kotlin', 'src/jvmMain/kotlin',
            'src/test/kotlin', 'src/test/java', 'src/androidUnitTest/kotlin',
        ];
        return roots
            .map(relative => path.join(module.dir, relative))
            .filter(dir => fs.existsSync(dir))
            .map(dir => this.node({
                id: `sources:${dir}`,
                label: path.relative(module.dir, dir).split(path.sep).join('/'),
                resourceUri: vscode.Uri.file(dir),
                collapsible: vscode.TreeItemCollapsibleState.Collapsed,
                icon: new vscode.ThemeIcon('folder'),
                context: 'fs',
            }));
    }

    private gradleFiles(): TreeNode[] {
        const root = this.ase.root;
        const names = [
            'settings.gradle.kts', 'settings.gradle', 'build.gradle.kts', 'build.gradle',
            'gradle.properties', 'gradle/libs.versions.toml',
            this.ase.profile.moduleFlagsFile,
            this.ase.profile.defaultFlagsFile,
        ].filter(name => name.length > 0);
        return [...new Set(names)]
            .map(name => path.join(root, name))
            .filter(file => fs.existsSync(file))
            .map(file => this.node({
                id: `gradle:${file}`,
                label: path.relative(root, file).split(path.sep).join('/'),
                resourceUri: vscode.Uri.file(file),
                collapsible: vscode.TreeItemCollapsibleState.None,
                icon: new vscode.ThemeIcon('file-code'),
                context: 'fs',
            }));
    }

    private readDir(dir: string, parentId: string): TreeNode[] {
        let entries: fs.Dirent[];
        try {
            entries = fs.readdirSync(dir, { withFileTypes: true });
        } catch {
            return [];
        }
        return entries
            .filter(entry => !entry.name.startsWith('.') && entry.name !== 'build')
            .sort((a, b) => Number(b.isDirectory()) - Number(a.isDirectory()) || a.name.localeCompare(b.name))
            .map(entry => {
                const fullPath = path.join(dir, entry.name);
                const isDir = entry.isDirectory();
                return this.node({
                    id: `${parentId}/${entry.name}`,
                    label: entry.name,
                    resourceUri: vscode.Uri.file(fullPath),
                    collapsible: isDir
                        ? vscode.TreeItemCollapsibleState.Collapsed
                        : vscode.TreeItemCollapsibleState.None,
                    icon: new vscode.ThemeIcon(isDir ? 'folder' : 'file'),
                    context: 'fs',
                });
            });
    }

    private node(params: Omit<TreeNode, 'collapsible'> & { collapsible: vscode.TreeItemCollapsibleState }): TreeNode {
        return params;
    }
}

function moduleBadge(module: ProjectModule): string | undefined {
    if (module.alwaysIncluded || module.flag === 'true') return 'source';
    if (module.flag === 'auto') return 'auto';
    if (module.flag === 'false') return 'maven';
    return undefined;
}

function listManifests(moduleDir: string): string[] {
    const manifests: string[] = [];
    const push = (file: string) => { if (fs.existsSync(file)) manifests.push(file); };
    push(path.join(moduleDir, 'AndroidManifest.xml'));
    push(path.join(moduleDir, 'src', 'main', 'AndroidManifest.xml'));
    const srcDir = path.join(moduleDir, 'src');
    if (!fs.existsSync(srcDir)) return manifests;
    for (const variant of fs.readdirSync(srcDir)) {
        push(path.join(srcDir, variant, 'AndroidManifest.xml'));
    }
    return manifests;
}
