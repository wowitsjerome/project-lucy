#!/usr/bin/env node
import { spawnSync } from 'node:child_process';
import { resolve, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
import { homedir } from 'node:os';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const [action, serial = 'emulator-5580'] = process.argv.slice(2);
if (!['install', 'owner', 'test-ui', 'test-managed', 'test-onboarding', 'home', 'status'].includes(action)) throw new Error('Usage: node scripts/device.mjs install|owner|test-ui|test-managed|test-onboarding|home|status [emulator-5580|emulator-5582|emulator-5586]');
if (!['emulator-5580', 'emulator-5582', 'emulator-5586'].includes(serial)) throw new Error('This helper only operates isolated Lucy emulators. Use Android Studio for a physical phone.');
const sdk = process.env.ANDROID_SDK_ROOT || process.env.ANDROID_HOME || resolve(homedir(), 'Library/Android/sdk');
const adb = resolve(sdk, 'platform-tools/adb');
const run = args => {
    const result = spawnSync(adb, ['-s', serial, ...args], { encoding: 'utf8' });
    process.stdout.write(result.stdout || ''); process.stderr.write(result.stderr || '');
    if (result.status !== 0 || /FAILURES!!!|INSTRUMENTATION_FAILED|Process crashed/.test(result.stdout)) process.exit(result.status || 1);
    return result.stdout;
};
const expected = { 'emulator-5580': 'Lucy_Launcher', 'emulator-5582': 'Lucy_Managed', 'emulator-5586': 'Lucy_Onboarding_Auto' }[serial];
if (!run(['emu', 'avd', 'name']).split(/\r?\n/).map(line => line.trim()).includes(expected)) throw new Error(`Expected isolated AVD ${expected}; refusing to operate another device`);
if (action === 'install') run(['install', '-r', '-t', resolve(root, 'app/build/outputs/apk/debug/app-debug.apk')]);
if (action === 'owner') {
    if (!['emulator-5582', 'emulator-5586'].includes(serial)) throw new Error('Device Owner is only enabled on managed/QA emulators');
    run(['shell', 'dpm', 'set-device-owner', 'dev.lucy.phone/.policy.LucyAdminReceiver']);
}
if (action === 'home' || action === 'install') {
    run(['shell', 'cmd', 'package', 'set-home-activity', 'dev.lucy.phone/.launcher.HomeActivity']);
    run(['shell', 'am', 'start', '-n', 'dev.lucy.phone/.launcher.HomeActivity']);
}
if (action.startsWith('test-')) {
    if (action === 'test-ui' && serial !== 'emulator-5580') throw new Error('LauncherTest requires the unrestricted Lucy_Launcher; Android blocks its test-harness activities under lock task. Use test-onboarding on the fresh QA fixture for managed-flow coverage.');
    if (action === 'test-managed' && serial !== 'emulator-5582') throw new Error('Managed tests require Lucy_Managed');
    if (action === 'test-onboarding' && serial !== 'emulator-5586') throw new Error('Onboarding fixtures only run on Lucy_Onboarding_Auto');
    run(['install', '-r', '-t', resolve(root, 'app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk')]);
    run(['shell', 'am', 'instrument', '-w', '-r', '-e', 'class', action === 'test-ui' ? 'dev.lucy.phone.LauncherTest' : action === 'test-onboarding' ? 'dev.lucy.phone.OnboardingFlowTest' : 'dev.lucy.phone.ManagedPolicyTest',
        ...(action === 'test-managed' ? ['-e', 'managed', 'true'] : action === 'test-onboarding' ? ['-e', 'qa', 'true'] : []), 'dev.lucy.phone.test/androidx.test.runner.AndroidJUnitRunner']);
}
if (action === 'status') {
    run(['shell', 'dumpsys', 'device_policy']);
    run(['shell', 'run-as', 'dev.lucy.phone', 'cat', '/data/user_de/0/dev.lucy.phone/files/policy-state.json']);
}
