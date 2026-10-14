import * as vscode from 'vscode';
import { KotlinLspBridge } from './lsp/kotlinLspBridge';
import { ProjectProfile, readProfile } from './profile/projectProfile';

const StateKeys = {
    VARIANT: 'ase.selectedVariant',
    APP_MODULE: 'ase.selectedAppModule',
    DEVICE: 'ase.selectedDevice',
} as const;

export class AseContext implements vscode.Disposable {
    readonly output = vscode.window.createOutputChannel('ASE');
    readonly bridge: KotlinLspBridge;
    private readonly modulesChanged = new vscode.EventEmitter<void>();
    private readonly selectionChanged = new vscode.EventEmitter<void>();
    readonly onDidChangeModules = this.modulesChanged.event;
    readonly onDidChangeSelection = this.selectionChanged.event;

    constructor(readonly root: string, private readonly extension: vscode.ExtensionContext) {
        this.bridge = new KotlinLspBridge(message => this.log(message));
    }

    get profile(): ProjectProfile {
        return readProfile(this.root);
    }

    get extensionContext(): vscode.ExtensionContext {
        return this.extension;
    }

    log(message: string): void {
        this.output.appendLine(`[ASE] ${message}`);
    }

    fireModulesChanged(): void {
        this.modulesChanged.fire();
    }

    get variant(): string {
        return this.extension.workspaceState.get<string>(StateKeys.VARIANT) ?? this.profile.variants[0] ?? 'debug';
    }

    get appModule(): string | undefined {
        return this.extension.workspaceState.get<string>(StateKeys.APP_MODULE) ?? this.profile.appModules[0];
    }

    get device(): string | undefined {
        return this.extension.workspaceState.get<string>(StateKeys.DEVICE);
    }

    async setVariant(variant: string): Promise<void> {
        await this.update(StateKeys.VARIANT, variant);
    }

    async setAppModule(gradlePath: string): Promise<void> {
        await this.update(StateKeys.APP_MODULE, gradlePath);
    }

    async setDevice(serial: string | undefined): Promise<void> {
        await this.update(StateKeys.DEVICE, serial);
    }

    dispose(): void {
        this.bridge.dispose();
        this.modulesChanged.dispose();
        this.selectionChanged.dispose();
        this.output.dispose();
    }

    private async update(key: string, value: string | undefined): Promise<void> {
        await this.extension.workspaceState.update(key, value);
        this.selectionChanged.fire();
    }
}
