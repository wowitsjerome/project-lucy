#!/usr/bin/env node
import { createHash } from 'node:crypto';
import { readFileSync } from 'node:fs';

const [apk, address] = process.argv.slice(2);
if (!apk || !address) throw new Error('Usage: node scripts/provisioning.mjs <signed-release.apk> <https-download-url>');
const url = new URL(address);
if (url.protocol !== 'https:' || url.username || url.password) throw new Error('Use a direct HTTPS APK download URL without embedded credentials');
const checksum = createHash('sha256').update(readFileSync(apk)).digest('base64url');
console.log(JSON.stringify({
    'android.app.extra.PROVISIONING_DEVICE_ADMIN_COMPONENT_NAME': 'dev.lucy.phone/dev.lucy.phone.policy.LucyAdminReceiver',
    'android.app.extra.PROVISIONING_DEVICE_ADMIN_PACKAGE_DOWNLOAD_LOCATION': url.href,
    'android.app.extra.PROVISIONING_DEVICE_ADMIN_PACKAGE_CHECKSUM': checksum,
    'android.app.extra.PROVISIONING_LEAVE_ALL_SYSTEM_APPS_ENABLED': true,
}, null, 2));
