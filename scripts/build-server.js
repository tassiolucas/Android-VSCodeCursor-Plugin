#!/usr/bin/env node
/**
 * Builds the LSP server via Gradle.
 *
 * Tries the system `gradle` first; falls back to the committed Gradle Wrapper
 * (`server/gradlew`) so a machine without a global Gradle still packages.
 */
const { spawnSync } = require('child_process');
const path = require('path');

const serverDir = path.join(__dirname, '..', 'server');
const isWin = process.platform === 'win32';

function run(cmd, args, opts) {
  const res = spawnSync(cmd, args, { stdio: 'inherit', shell: isWin, ...opts });
  return res.status === 0;
}

// 1. System Gradle
const sysGradle = isWin ? 'gradle.bat' : 'gradle';
const probe = spawnSync(isWin ? 'where' : 'which', [sysGradle], { stdio: 'pipe', shell: isWin });
if (probe.status === 0) {
  if (run('gradle', ['-p', 'server', 'installDist'])) process.exit(0);
  process.exit(1);
}

// 2. Gradle Wrapper (committed in the repo)
const wrapper = path.join(serverDir, isWin ? 'gradlew.bat' : 'gradlew');
console.log('System gradle not found; using Gradle Wrapper:', wrapper);
const ok = run(wrapper, ['installDist', '--no-daemon'], { cwd: serverDir });
process.exit(ok ? 0 : 1);
