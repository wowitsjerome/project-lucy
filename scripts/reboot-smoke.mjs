#!/usr/bin/env node
import { spawn, spawnSync } from 'node:child_process';
import { resolve, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
import { homedir } from 'node:os';

const sdk = process.env.ANDROID_SDK_ROOT || resolve(homedir(), 'Library/Android/sdk');
const adb = resolve(sdk, 'platform-tools/adb');
const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const cold = process.argv.includes('--cold');
if (process.argv.slice(2).some(arg => arg !== '--cold')) throw new Error('Usage: node scripts/reboot-smoke.mjs [--cold]');
const pin = process.env.LUCY_TEST_SCREEN_PIN;
if (!/^\d{4,16}$/.test(pin || '')) throw new Error('Set LUCY_TEST_SCREEN_PIN to the disposable emulator screen-lock PIN');
const call = (...args) => spawnSync(adb, ['-s', 'emulator-5582', ...args], { encoding: 'utf8', timeout: 60_000 });
if (!call('emu', 'avd', 'name').stdout.split(/\r?\n/).includes('Lucy_Managed')) throw new Error('Requires the isolated Lucy_Managed AVD');
const wait = ms => new Promise(resolve => setTimeout(resolve, ms));
for (let cycle = 1; cycle <= 3; cycle++) {
    console.log(`Reboot cycle ${cycle}/3 (${cold ? 'cold emulator restart' : 'Android warm reboot'})`);
    const synced = call('shell', 'sync');
    if (synced.status !== 0) throw new Error('Could not sync the emulator filesystem');
    const reboot = cold ? call('shell', 'svc', 'power', 'shutdown') : call('reboot');
    // A successful guest shutdown can close ADB before shell reports an exit code.
    if (reboot.status !== 0 && !(cold && !reboot.error && !reboot.stderr.trim() && !reboot.stdout.trim()))
        throw new Error(reboot.stderr || reboot.error?.message || 'Android rejected the restart request');
    if (cold) {
        await wait(8_000);
        if (call('get-state').stdout.trim() === 'device') throw new Error('Android did not shut down cleanly');
        call('emu', 'kill'); // Some emulator versions exit on guest shutdown; others stay open.
        await wait(3_000);
        const childEnv = { ...process.env };
        delete childEnv.LUCY_TEST_SCREEN_PIN;
        const child = spawn(process.execPath, [resolve(root, 'scripts/emulator.mjs'), 'managed'],
            { cwd: root, env: childEnv, detached: true, stdio: 'ignore' });
        child.unref();
    }
    await wait(4_000);
    let booted = false;
    for (let attempt = 0; attempt < 90; attempt++) {
        if (call('shell', 'getprop', 'sys.boot_completed').stdout.trim() === '1') { booted = true; break; }
        await wait(1_000);
    }
    if (!booted) throw new Error('Emulator did not boot within 90 seconds');
    // sys.boot_completed can precede a responsive keyguard. Wait, unlock, and verify
    // the actual lock state; never mistake a test running before unlock for success.
    await wait(5_000);
    let unlocked = false;
    for (let attempt = 0; attempt < 3; attempt++) {
        call('shell', 'input', 'keyevent', 'KEYCODE_WAKEUP');
        await wait(1_000);
        call('shell', 'input', 'swipe', '500', '1700', '500', '500', '400');
        await wait(1_500);
        call('shell', 'input', 'text', pin);
        call('shell', 'input', 'keyevent', 'KEYCODE_ENTER');
        await wait(3_000);
        if (call('shell', 'dumpsys', 'trust').stdout.includes('deviceLocked=0')) { unlocked = true; break; }
    }
    if (!unlocked) throw new Error('Could not unlock the emulator; check the screen PIN and keyguard');
    const result = call('shell', 'am', 'instrument', '-w', '-e', 'class', 'dev.lucy.phone.PostBootTest', '-e', 'managed', 'true', 'dev.lucy.phone.test/androidx.test.runner.AndroidJUnitRunner');
    console.log(result.stdout);
    if (result.status !== 0 || !result.stdout.includes('OK (1 test)')) throw new Error('Post-boot verification failed');
}
