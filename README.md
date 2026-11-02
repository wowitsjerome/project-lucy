# Lucy

A native Android minimalist launcher and local Device Policy Controller. Kotlin, Compose, Android 11+.

**Daily Wi-Fi (0.3.6):** Home → Device controls → Wi-Fi works without administrator authentication. Toggle Wi-Fi, select a saved or nearby network, or enter an SSID and password. Nearby scans require Android Location to be enabled; Lucy offers an explicit button when it is off. Enterprise and legacy WEP setup still require administrator assistance. Network passwords go to Android's Wi-Fi configuration and are not stored in Lucy's policy or logs. Filtering and daily app restrictions remain active.

**Start with launcher mode in the emulator.** Managed mode is implemented but is not certified for daily physical-phone use. See [acceptance results](docs/acceptance.md) for tested behavior and remaining gates.

**Onboarding:** read [the administrator walkthrough](docs/onboarding.md). The flow is **Connect & accounts → Choose & set up apps → Test filtering → Review & finish**. Do not enable daily restrictions before installing, signing in, and verifying internet access.

## Run locally

Prerequisites: Android Studio, JDK 17+ (Studio's bundled JBR works), Android SDK platform 35, and Node 18+ for the emulator helpers. The helpers default to the installed Android 16 Google Play ARM64 image on this Mac.

```sh
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :app:assembleDebug
node scripts/emulator.mjs launcher
```

Keep that emulator running. In another terminal:

```sh
node scripts/device.mjs install emulator-5580
```

Lucy becomes the virtual phone's HOME app. App buttons launch installed Android apps; Home returns to Lucy. Administration lets you create a PIN and choose visible apps. Launcher mode does not change device policies. No default administrator credential is built in.

Alternatively, open this directory in Android Studio, select a virtual device, and Run. Choose **Settings → Apps → Default apps → Home app → Lucy** if running without the helper.

The helpers use `.local/avd/Lucy_Launcher` and `.local/avd/Lucy_Managed`, on ports 5580 and 5582. `node scripts/emulator.mjs qa` creates a separate disposable `Lucy_Onboarding_Auto` on port 5586 for onboarding fixtures. They verify AVD names before issuing device commands and do not modify the existing `Pixel_9a` AVD. They never connect to a physical phone. Override `ANDROID_SDK_ROOT` and `LUCY_SYSTEM_IMAGE` for another SDK installation/image; the supplied emulator configurations require ARM64 hosts/images.

## Check the implementation

```sh
bash scripts/check.sh
node scripts/device.mjs test-ui emulator-5580
```

The build script compiles debug, instrumentation, and release variants; runs unit tests and lint; and checks helper syntax. Test reports are in `app/build/reports/`. The UI tests exercise HOME, device controls, protected administration, and launching Phone/returning Home on the supplied Google emulator image. Managed and reboot tests require a **disposable** Device Owner emulator; follow [the test runbook](docs/provisioning-and-recovery.md).

Debug APK: `app/build/outputs/apk/debug/app-debug.apk`. It is signed with the local Android debug key, marked test-only, and deliberately retains ADB. Managed status identifies the development exception; never distribute it as a hardened build.

Release APK: `app/build/outputs/apk/release/app-release-unsigned.apk` unless release-signing environment variables are supplied. Release policy includes the debugging restriction and has no test-only flag.

## What is implemented

Lucy 0.3.3 cycles Sound → Vibrate → Silent from the Home-screen icon, with separate Sound, Vibrate, and Silent
buttons in Device controls. These change call/notification ringer mode; alarm
and media volume remain unchanged. Android may require one-time Do Not Disturb
policy access to change silent mode. During supervised setup, open
Administration → Security & recovery → Allow Lucy sound-mode access and enable
Lucy. Normal daily sound changes then work without opening Android Settings.

- Text HOME, ordered installed-app list, app launch/return, brightness and ringtone-volume controls.
- Guided administration, persistent app drafts, required/optional app guidance, direct Settings/accounts/Play/browser tools, sign-in checklist, and first-activation readiness gate.
- Startup-safe administrator entry: loading/read failures cannot be mistaken for a request to create a new PIN; existing credentials and app choices survive updates.
- Optional daily Chrome: select it in **Choose & set up apps**, review, and apply the draft. Removing approval hides it again in managed mode. Chrome's own DNS settings and web content are not fully controlled by Lucy; see the [setup and filtering limits](docs/onboarding.md#which-apps-belong-on-the-phone).
- Device Owner provisioning components, package inventory, explicit system dependencies, persistent HOME, multi-app lock task, app/install/settings restrictions, read-back checks, and compensating rollback.
- Enforced Cloudflare Families Private DNS and safe category probes, with explicit network failure status.
- Salted PIN/recovery verifiers, persistent backoff, expiring admin sessions, protected UI, local policy log, and backup exclusion.
- Supervised maintenance with foreground status, exact expiry alarm, persistent restoration state, boot/package reconciliation, and signed-update support.
- Explicit, warned temporary internet for supervised setup (15 minutes maximum), with offline strict-DNS restoration and separate native normal-internet/category test results.
- QR-enrollment payload generator and authenticated decommissioning confirmation.

Live filtering requires outbound TLS on port 853. This development host could not reach that port during verification; the app reports failure instead of claiming filtering is active. Domain filtering cannot inspect content inside allowed apps or stop arbitrary custom DNS/direct-IP connections.

Temporary internet can let an administrator install apps and sign in, but it is **unfiltered**, requires confirmation and a timer, and cannot qualify the device for daily use. New managed onboarding remains unlocked until filtering passes on a compatible network. Use the three-button navigation setting on the current emulator; gesture navigation can incorrectly invoke the blocked Pixel Launcher.

Warm reboot testing is unresolved on the supplied Android 16 emulator: the AVD became unresponsive. The cold-start helper uses a synced, clean Android shutdown; see the acceptance record for separate results and known emulator limitations.

Physical Pixel/Samsung compatibility, real cellular/emergency behavior, QR enrollment during physical Setup Wizard, Play updates under actual accounts, faulty-release recovery, and the three-day trial remain release gates. No backend account or paid service subscription is required. Production signing credentials are yours to supply and retain.

## Release signing

Retain a dedicated keystore and back it up offline before provisioning a physical phone. Inject these variables through your environment/secret manager:

```text
LUCY_KEYSTORE       absolute path to the keystore
LUCY_STORE_PASSWORD keystore password
LUCY_KEY_ALIAS      signing-key alias
LUCY_KEY_PASSWORD   signing-key password
```

Then run `./gradlew :app:assembleRelease`. Upgrade using the same key and a higher `versionCode`. Never commit keystores or passwords. Switching from the debug key to the release key requires a fresh installation/provisioning, not an in-place update.

Implementation plan: [minimalist-android-phone-mvp.md](minimalist-android-phone-mvp.md).
