#!/usr/bin/env node
import { existsSync, mkdirSync, writeFileSync } from 'node:fs';
import { homedir } from 'node:os';
import { resolve, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
import { spawnSync } from 'node:child_process';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const mode = process.argv[2] || 'launcher';
if (!['launcher', 'managed', 'qa'].includes(mode)) throw new Error('Usage: node scripts/emulator.mjs launcher|managed|qa');
const sdk = process.env.ANDROID_SDK_ROOT || process.env.ANDROID_HOME || resolve(homedir(), 'Library/Android/sdk');
const name = { launcher: 'Lucy_Launcher', managed: 'Lucy_Managed', qa: 'Lucy_Onboarding_Auto' }[mode];
const port = { launcher: 5580, managed: 5582, qa: 5586 }[mode];
const image = process.env.LUCY_SYSTEM_IMAGE || 'system-images/android-36/google_apis_playstore/arm64-v8a/';
if (!existsSync(resolve(sdk, image, 'system.img'))) throw new Error(`Install ${image} with Android Studio SDK Manager first`);
const avdRoot = resolve(root, '.local/avd');
const directory = resolve(avdRoot, `${name}.avd`);
mkdirSync(directory, { recursive: true });
if (!existsSync(resolve(directory, 'config.ini'))) {
  const settings = {
    'AvdId': name, 'avd.ini.displayname': name.replaceAll('_', ' '), 'avd.ini.encoding': 'UTF-8',
    'abi.type': 'arm64-v8a', 'hw.cpu.arch': 'arm64', 'hw.cpu.ncore': '4', 'hw.ramSize': '2048',
    'hw.lcd.width': '1080', 'hw.lcd.height': '1920', 'hw.lcd.density': '420', 'hw.keyboard': 'yes',
    'hw.gpu.enabled': 'yes', 'hw.gpu.mode': 'auto', 'hw.battery': 'yes', 'hw.gps': 'yes',
    'hw.camera.back': 'emulated', 'hw.camera.front': 'emulated', 'hw.audioInput': 'no',
    'disk.dataPartition.size': '4G', 'image.sysdir.1': image, 'tag.id': image.includes('playstore') ? 'google_apis_playstore' : 'google_apis',
    'PlayStore.enabled': image.includes('playstore') ? 'true' : 'false', 'showDeviceFrame': 'no',
    'fastboot.forceColdBoot': 'yes', 'fastboot.forceFastBoot': 'no',
  };
  writeFileSync(resolve(directory, 'config.ini'), Object.entries(settings).map(([k,v]) => `${k}=${v}`).join('\n') + '\n');
  writeFileSync(resolve(avdRoot, `${name}.ini`), `avd.ini.encoding=UTF-8\npath=${directory}\ntarget=android-36\n`);
}
console.log(`Starting isolated ${name} on emulator-${port}. Existing personal AVDs are untouched.`);
const result = spawnSync(resolve(sdk, 'emulator/emulator'), ['-avd', name, '-port', String(port), '-no-snapshot', '-no-boot-anim', '-netdelay', 'none', '-netspeed', 'full', ...(mode === 'qa' ? ['-no-window'] : [])],
  { stdio: 'inherit', env: { ...process.env, ANDROID_AVD_HOME: avdRoot, ANDROID_SDK_ROOT: sdk } });
process.exit(result.status || 0);
