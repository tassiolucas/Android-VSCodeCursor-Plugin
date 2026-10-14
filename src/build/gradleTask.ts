import * as path from 'path';
import * as vscode from 'vscode';
import { qualifiedTask, variantTaskName } from './gradleTaskNames';

export { qualifiedTask, variantTaskName };

const TASK_SOURCE = 'ASE';

export function runGradleTask(params: { root: string; tasks: string[]; label: string }): Promise<boolean> {
    const gradlew = process.platform === 'win32' ? path.join(params.root, 'gradlew.bat') : './gradlew';
    const task = new vscode.Task(
        { type: 'shell', task: params.label },
        vscode.TaskScope.Workspace,
        params.label,
        TASK_SOURCE,
        new vscode.ShellExecution(gradlew, params.tasks, { cwd: params.root }),
    );
    task.presentationOptions = { reveal: vscode.TaskRevealKind.Always, panel: vscode.TaskPanelKind.Dedicated };

    return new Promise(resolve => {
        let execution: vscode.TaskExecution | undefined;
        const listener = vscode.tasks.onDidEndTaskProcess(event => {
            if (event.execution !== execution) return;
            listener.dispose();
            resolve(event.exitCode === 0);
        });
        vscode.tasks.executeTask(task).then(
            started => { execution = started; },
            () => { listener.dispose(); resolve(false); },
        );
    });
}
