# Provisioning, maintenance, and recovery

## Local Device Owner test

Use the isolated `Lucy_Managed` emulator only. Owner provisioning changes device-wide behavior. No USB phone is required.

1. Build with `bash scripts/check.sh`.
2. Start `node scripts/emulator.mjs managed` and wait for Android to boot.
3. Run `node scripts/device.mjs install emulator-5582`, then `node scripts/device.mjs owner emulator-5582`. Do this before adding Google accounts or profiles.
4. In Lucy, open Administration. Create an eight-digit-or-longer PIN and save the generated recovery code offline. Neither credential is preset.
5. Set a **separate Android screen-lock PIN** with the setup button. Allow Lucy under **Alarms & reminders** using “Allow maintenance timer” if it is not already exempt.
6. Follow [the onboarding walkthrough](onboarding.md): Connect & accounts → Choose & set up apps → Test filtering → Review & finish. Install apps and sign into accounts before enabling daily mode. App choices persist as a draft.
7. First activation requires the checklist, screen lock, timer, and a live filtering pass. A failed test leaves setup unlocked. Confirm the resulting state. “Development · ADB enabled” identifies the debug exception; “Attention required” means verification is incomplete.

ADB components, if operating manually:

```sh
adb -s emulator-5582 shell dpm set-device-owner dev.lucy.phone/.policy.LucyAdminReceiver
adb -s emulator-5582 shell cmd package set-home-activity dev.lucy.phone/.launcher.HomeActivity
```

`node scripts/device.mjs status emulator-5582` displays policies and the local event log, but not credentials.

### Instrumented management test

The managed test creates random temporary administrator credentials and changes policies. Run it on a **fresh** test instance before interactive administrator setup. Configure the Android screen lock and alarm access first, then:

```sh
node scripts/device.mjs test-managed emulator-5582
```

The lifecycle test verifies Android controls independently from network-dependent filtering. If initial filtering cannot pass, it checks that first-time activation remains blocked, then seeds an explicitly simulated existing-device policy solely to test restoration. That fixture is not an onboarding acceptance pass. The live-filter test is skipped when strict Private DNS cannot be configured or the allowed-domain control cannot resolve. If the control succeeds but category blocking fails, the test fails. A skipped test is not a filtering pass.

For the onboarding regression suite, use a **fresh disposable** `Lucy_Onboarding_Auto`, never the user's managed AVD:

```sh
node scripts/emulator.mjs qa
node scripts/device.mjs install emulator-5586
node scripts/device.mjs owner emulator-5586
# Configure this disposable device's Android screen lock and alarm permission first.
node scripts/device.mjs test-onboarding emulator-5586
```

The QA instance runs without a window. Tests assert its exact AVD identity before creating fixture credentials. The suite checks setup-tool navigation, draft persistence, blocked first activation, explicit temporary-internet authorization, simulated expiry/startup strict-DNS restoration, and existing-policy draft application. It does not sign into real accounts. Capture/inspection artifacts contain fixture screens only.

After the managed test, verify three reboot/unlock cycles:

```sh
read -s LUCY_TEST_SCREEN_PIN
export LUCY_TEST_SCREEN_PIN
node scripts/reboot-smoke.mjs
unset LUCY_TEST_SCREEN_PIN
```

Enter the disposable emulator's **Android screen-lock PIN**, not its administrator PIN. The helper verifies the AVD name and actual unlock state, then checks Device Owner, restrictions, required dependencies, HOME, and running lock task after every reboot. It never operates a physical phone.

On this Android 16 image/emulator combination, warm `adb reboot` became unresponsive during testing. `node scripts/reboot-smoke.mjs --cold` instead syncs storage, requests a clean Android shutdown, and restarts the same AVD three times without deleting its disk. Abruptly killing the emulator immediately after installing APKs caused missing package files in an earlier run; use clean shutdowns. Record cold-start and warm-reboot results separately; cold-start success does not close the warm-reboot or physical-device gate.

To repeat from a clean Lucy test state, use debug-only owner removal and clear **only this generated emulator app state**:

```sh
adb -s emulator-5582 shell dpm remove-active-admin dev.lucy.phone/.policy.LucyAdminReceiver
adb -s emulator-5582 shell pm clear dev.lucy.phone
adb -s emulator-5582 shell appops set dev.lucy.phone SCHEDULE_EXACT_ALARM allow
node scripts/device.mjs owner emulator-5582
```

This erases the test administrator credentials and policy journal. It does not reset the whole virtual device. Removal is possible because the debug APK is test-only; this is not a release recovery path. Hidden packages are restored/reconciled when the new test policy is applied. Prefer a new emulator for a completely fresh baseline.

## Physical QR enrollment

Pending hardware validation. Back up device data, record the exact firmware and required account/FRP credentials, and use a factory-reset test phone.

1. Build a release APK with a retained signing key. Host the exact APK at a directly downloadable HTTPS URL; redirects/authentication must not prevent Setup Wizard from downloading it.
2. Generate its enrollment payload:

```sh
node scripts/provisioning.mjs path/to/signed-release.apk https://your-host/lucy.apk
```

3. Encode the JSON output into a QR code using an offline QR encoder. The payload contains the receiver component, URL, and URL-safe SHA-256 APK checksum. Regenerate it whenever the APK changes.
4. At the reset device's welcome screen, use the manufacturer's Android Enterprise QR-enrollment gesture (commonly six taps). Scan, connect to Wi-Fi, and complete provisioning.
5. Verify Lucy is Device Owner; complete credentials, screen lock, timer permission, app installation/sign-in, and dependency review. Apply policy only after the required apps are installed.
6. Run the acceptance suite before daily use. A successful payload generation/build does not validate OEM Setup Wizard compatibility.

No APK has been hosted or deployed by this repository. Google Play EMM enrollment is not used. Consult [Android provisioning](https://developer.android.com/work/dpc/dedicated-devices) and [Android 12 DPC changes](https://developer.android.com/work/versions/android-12).

## Maintenance and upgrades

Authenticate → Open supervised setup → retain physical custody → install/update/sign in → review/apply draft (or end session without applying) → verify status.

Maintenance temporarily permits installation and unhides blocked apps, including Play/browser. DNS stays filtered unless the administrator separately confirms a warned, at-most-15-minute temporary-internet exception. Strict DNS restores on expiry/completion/restart, even when offline; the earlier deadline wins. Administration locks when leaving Lucy; authenticate again on return. The maintenance lease is independent of the two-minute admin session and lasts at most 30 minutes. An exact alarm plus foreground service handles expiry; startup/boot/update restoration uses the persisted policy. If timer access is unavailable, timed exceptions are refused.

The installed-app list can be changed without rebuilding. Removing an essential app is rejected. New optional packages appear after installation/refresh and require selection plus checklist review. System/role dependencies are retained separately from visible applications. Apply the draft directly from maintenance, or end maintenance without applying it to restore the existing list and retain the draft.

Update third-party apps through Play. Update Lucy with the same signing key and increasing `versionCode`. A DPC update terminates its admin session and triggers policy restoration; authenticate again if further maintenance is needed. A network failure can leave **Attention required** after device restrictions have been restored. Use the policy error list to distinguish network failure from missing controls.

## Recovery and decommissioning

- Forgotten PIN: “Use recovery code” rotates the PIN and issues a replacement code. The previous code stops working. Failed recovery attempts share the PIN backoff.
- Interrupted maintenance: reopen Lucy or reboot. Confirm restrictions restore and errors are visible. Do not return the phone while verification is incomplete.
- DNS unavailable: use another network that permits DNS-over-TLS to `family.cloudflare-dns.com:853`. Explicit temporary setup internet can enable sign-in/install work; it cannot complete managed onboarding or count as a filtering pass. Lucy never switches to unfiltered DNS automatically.
- Broken DPC or lost PIN and recovery code: use the device-specific recovery/reset procedure recorded during hardware testing. This deletes data and may require the previously associated Google account. Do not assume factory reset removes FRP requirements.
- Planned retirement: back up data and prepare accounts/FRP, authenticate, enter maintenance, and select Decommission device. The app requires the exact phrase `ERASE THIS DEVICE` before requesting Android's wipe. This path is implemented but has not been invoked on physical hardware.

Persisted policy failures are never hidden by a “Ready” status. No software-only recovery procedure is claimed for a DPC that cannot start; an upgrade/recovery rehearsal on resettable hardware is required.
