import * as path from 'path';
import Mocha from 'mocha';
import * as glob from 'glob';

export async function run(): Promise<void> {
    const mocha = new Mocha({
        ui: 'tdd',
        color: true,
        timeout: 120000,
    });

    const testsRoot = __dirname;
    const files = await glob.glob('**/*.test.js', { cwd: testsRoot });

    files.forEach((f) => mocha.addFile(path.resolve(testsRoot, f)));

    try {
        await new Promise<void>((resolve, reject) => {
            mocha.run((failures: number) => {
                if (failures > 0) reject(new Error(`${failures} tests failed.`));
                else resolve();
            });
        });
    } catch (err) {
        console.error(err);
        throw err;
    }
}