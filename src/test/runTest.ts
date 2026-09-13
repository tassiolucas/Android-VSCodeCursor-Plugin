import * as path from 'path';
import { runTests } from '@vscode/test-electron';

async function main(): Promise<void> {
    try {
        const extensionDevelopmentPath = path.resolve(__dirname, '..', '..');
        const extensionTestsPath = path.resolve(__dirname, 'suite', 'index.js');
        const workspacePath = process.env.ASE_TEST_WORKSPACE
            ? process.env.ASE_TEST_WORKSPACE
            : path.join(extensionDevelopmentPath, 'test-workspace');

        await runTests({
            extensionDevelopmentPath,
            extensionTestsPath,
            launchArgs: [workspacePath, '--disable-extensions'],
        });
        console.log('Integration tests passed.');
    } catch (err) {
        console.error('Integration tests failed:', err);
        process.exit(1);
    }
}

void main();