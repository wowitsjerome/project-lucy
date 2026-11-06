# Acceptance record

Status: onboarding/administration revision implemented for local development; verification scope is recorded below. **Not approved for daily physical-device deployment.**

## Environment

- Host: Apple Silicon Mac, bundled Android Studio JBR 21.
- Build: Gradle 8.13, AGP 8.13.2, Kotlin 2.1.20, compile/target SDK 35, minimum API 30.
- Emulators: isolated `Lucy_Launcher` (5580) and `Lucy_Managed` (5582).
- System image: Google Play ARM64 Android 16/API 36, build `BE2A.250530.026.D1`.
- App: `dev.lucy.phone`, version 0.4.0; debug/test-only policy deliberately retains ADB. Earlier-version evidence below is historical unless explicitly rerun.
- Physical baseline used for the latest focused checks: Samsung SM-S918U, Android 14. Broader carrier/device certification remains outstanding.

## Evidence and scope

### Authenticator-first administrator access · 0.4.0 · 2026-11-06

- Added local RFC 6238 TOTP enrollment under **Administration → Security & recovery**. Lucy shows the provisioning QR or an explicitly revealed grouped manual setup key only for the active in-memory enrollment, requires a valid six-digit code before enabling it, accepts SHA-1/30-second codes with a one-step clock-skew window, and defaults subsequent Administration login to the authenticator. The administrator PIN remains behind an explicit **Use backup PIN** action.
- The 160-bit TOTP secret is encrypted at rest with an app-specific Android Keystore AES-256-GCM key. The verifier secret is absent from `admin.json`, policy state, and logs. Leaving or locking Administration clears pending enrollment material; recovery rotates the PIN/recovery code and disables the enrolled authenticator.
- Authenticator, PIN, and recovery failures share the existing persistent exponential backoff. Each accepted TOTP time-step is persisted and cannot be accepted again. Authenticator success issues only a 30-second inactivity-based in-memory admin token; interaction within Administration refreshes it, while supervised maintenance and temporary-internet authorization remain separate.
- `bash scripts/check.sh` passed: debug and release assembly, unit tests, Android lint, instrumentation compilation, and helper syntax. Unit coverage includes RFC 6238 vectors, Base32/provisioning compatibility, malformed input, clock skew, and replay rejection.
- On the isolated `Lucy_Launcher` emulator, `TotpSecretStoreTest` passed and confirmed Keystore encryption round-trips without plaintext or Base64-plaintext persistence. The opt-in `AuthenticatorFlowTest` passed from a fully uninstalled/reinstalled Lucy fixture and exercised setup, verified enrollment, authenticator-first UI login, replay rejection, the rendered QR screen, the explicit backup-PIN option, and recovery disabling TOTP. All three existing `LauncherTest` UI regressions also passed after the flow.
- Upgraded the connected Samsung SM-S918U in place from 0.3.12 (versionCode 17) to 0.4.0 (versionCode 18) during supervised maintenance, then installed the tested same-version revision containing the manual setup key and 30-second inactivity window. The installed APK SHA-256 exactly matched the tested local artifact. The upgrades preserved PIN/recovery verifiers, the enrolled TOTP state with zero failed attempts, and all 33 allowed/Home apps. Process-restart reconciliation removed the maintenance lease, restored lock task, retained policy revision 17, and reported no policy errors or pending restoration.

### Persisted appearance modes · 0.3.12 · 2026-11-06

- Added ungated **Phone controls → Appearance** choices for System, Light, and Dark. System follows Android; explicit Light/Dark apply across every Lucy screen. The preference is synchronously persisted in device-protected app storage and is independent of policy state.
- `bash scripts/check.sh` passed: unit tests, Android lint, debug/instrumentation/release compilation, and helper/static checks. Unit coverage verifies safe stored-value parsing, System resolution, and WCAG AA contrast for core text/background pairs. Launcher instrumentation covers all three controls and persistence across Activity recreation.
- Installed on Samsung SM-S918U as versionCode 17. Live inspection verified Light and Dark on Phone controls and Dark on the main launcher, including readable status/navigation bars, selected controls, secondary text, disabled controls, app labels, and footer actions. The original System preference was restored and read back from persisted storage.
- Switching appearance did not change policy revision 16, the 33 allowed/Home apps, maintenance state, or lock-task enforcement; policy errors remained empty. A physical reboot persistence test was not performed in this focused pass.

### Short-lived administrator session · 0.3.11 · 2026-11-06

- Successful PIN entry now creates the intended two-minute in-memory session. Leaving for Home or an unrelated app hides management UI but does not discard a still-valid token; explicitly reopening Administration during the window does not ask for the PIN again. **Lock administration**, timeout, activity recreation, process restart, and credential changes discard access.
- A Lucy-launched setup tool may preserve the active admin screen across one expected pause; the grant is single-use and canceled after a failed/no-op launch. Lucy's lock-task exit has a separate one-time, 15-second in-process handoff because that OEM transition recreates Home. Neither mechanism survives process restart or extends maintenance.
- `bash scripts/check.sh` passed: unit tests, Android lint, debug/instrumentation/release compilation, and helper/static checks. New unit tests cover ordinary pauses returning away from the admin screen, single-use external-tool grants, canceled/consumed grants, and expiry/single-use behavior of the activity-recreation handoff.
- Installed on the managed Samsung SM-S918U as versionCode 16. Live testing confirmed supervised setup and Lucy-launched system Settings return to authenticated Administration, and the user confirmed leaving Administration and reopening it seconds later no longer prompts for the PIN. Test cleanup restored lock task, retained all 33 allowed/Home apps at policy revision 16, and reported no policy errors.

### Consolidated Phone controls · 0.3.9 · 2026-11-06

- Renamed the ungated everyday destination from **Device controls** to **Phone controls** and moved Home visibility under **Choose Home screen apps**. The Home footer now contains only **Phone controls** and PIN-protected **Administration**.
- `bash scripts/check.sh` passed after updating launcher, sound, and Wi-Fi instrumentation coverage for the new navigation structure.
- Updated the managed Samsung SM-S918U from 0.3.8 (versionCode 13) to 0.3.9 (versionCode 14). Live UI read-back confirmed **Phone controls** contains sound, launcher brightness, **Choose Home screen apps**, and Wi-Fi; the nested page returns to Phone controls. Policy revision 15 retained all 32 allowed and Home apps, reported no errors, ended maintenance, and restored lock task.

### Home visibility and administrator boundary · 0.3.8 · 2026-11-06

- `bash scripts/check.sh` passed: debug, instrumentation, and release compilation; unit tests; Android lint; and helper/static checks. Model tests cover legacy migration, Home/allowlist separation, essential-app visibility, invalid Home entries, and pruning an uninstalled optional app from both lists. Launcher instrumentation coverage was extended to enter **Edit Home** without authentication before confirming **Administration** remains PIN-protected; that instrumentation compiled but was not executed on this managed phone.
- Updated the managed Samsung SM-S918U from 0.3.7 (versionCode 12) to 0.3.8 (versionCode 13) during supervised maintenance. Update recovery restored daily restrictions and applied policy revision 15 with no policy errors.
- The legacy revision-14 policy had 32 allowed/visible apps and no `homePackages` field. On-device read-back after migration showed 32 allowed apps, 32 Home apps, identical package sets, and `lastVerifiedRevision` 15, so the upgrade hid nothing.
- Live UI inspection showed the Home list alphabetized, the three footer destinations fitting on the Samsung display, **Edit Home apps** opening without the administrator PIN, optional-app checkboxes enabled, and required-phone-function checkboxes selected and disabled. No claim is made here for a full physical hide/restore interaction; its allowlist separation and validation constraints are covered by unit tests, while the interaction itself remains a focused manual regression check before handoff.

### OEM in-call interface support · 0.3.7 · 2026-11-06

- Installed on Samsung SM-S918U after an outgoing call could connect while lockdown blocked Samsung's separate `com.samsung.android.incallui` activity, leaving no on-device call controls.
- Lucy now discovers trusted system/default-dialer services that declare the standard `android.telecom.IN_CALL_SERVICE_UI` metadata, keeps those packages available, and includes them in the lock-task allowlist. Existing managed policies migrate automatically; ordinary third-party calling apps do not gain access through this rule.
- The physical upgrade migrated policy revision 13 to 14. `com.samsung.android.incallui` was unhidden, unsuspended, recorded as system in-call support, and present in the active lock-task package list with no policy errors.
- Physical verification passed: an outgoing call displayed the full Samsung in-call interface and the user could control and end the call while daily lockdown remained active.
- Main unit tests, Android lint, debug/instrumentation/release compilation, and diff checks passed. `CallUiSupportTest` compiled; replacing the installed test APK after lockdown restoration was correctly rejected by the installation restriction, so the live policy read-back and manual call supplied the physical evidence.

### Daily Wi-Fi controls · 0.3.6 · 2026-11-02

- Installed on Samsung SM-S918U. `WifiControlsTest.dailyWifiControlsKeepPolicyEnforced` passed on the physical phone: reached Device controls → Wi-Fi without authentication, toggled Wi-Fi, restored its initial enabled state, and returned HOME. Lock task stayed active, the package allowlist stayed unchanged, installation restrictions stayed enforced, unrestricted Settings remained unapproved, and no maintenance lease was created.
- After reconnection, all five normal internet/domain filtering checks passed on the new network handle; policy errors and the crash buffer were empty. Build, unit tests, lint, test APK compilation, and release assembly passed.
- Implemented saved-network connection and new open/WPA2/WPA3 network configuration through Device Owner Wi-Fi APIs. Joining a new network with a real password, enterprise networks, captive portals, and hidden networks were not physically verified. Nearby scanning requires the location permission and Android Location switch; the user must explicitly enable that switch if it is off. The dedicated screen never opens Settings or changes filtering policy.

### Startup administrator routing · 0.2.2 · 2026-09-09

- Initial administrator state is now unknown, not false. The administration entry remains disabled while loading, setup links are suppressed, and unreadable/inconsistent managed credentials show a retry page without allowing replacement. A completed read corrects a stale setup page while preserving a newly issued recovery code until acknowledgment.
- Build: debug, instrumentation, unsigned release, and helper/static checks passed. **32 unit tests passed**; lint reports 0 errors and 18 warnings. Five new tests cover loading, existing credentials, genuine first setup, missing managed credentials, and read failures.
- `StartupAdministrationTest.slowStartupUsesExistingPinAndChromeRemainsSelectable`: **passed** in 20.324 seconds on `Lucy_Onboarding_Auto`. It deliberately holds the loading work queue, recreates Home, attempts to tap the disabled administration entry, verifies no new-PIN form appears, releases loading, signs in with the unchanged fixture PIN, and toggles Chrome's enabled checkbox without changing the effective policy. The original draft is restored. This test uses a host-free Compose rule to avoid launching test-harness activities blocked by lock task.
- `ChromePolicyTest.chromeApprovalLaunchAndRevocation`: rerun on 0.2.2 and **passed** in 33.082 seconds. Approved Chrome opens under lock task, Home returns to Lucy, revocation hides Chrome without uninstalling it, and other daily restrictions stay enforced.
- User's `Lucy_Managed` emulator updated **in place from 0.1.0 to 0.2.2** after the user opened supervised maintenance. Installed versionCode 4 confirmed. The administrator-file fingerprint and complete effective app list matched before/after; no credential reset, owner removal, or app-data clearing. Upgrade ended maintenance and restored daily restrictions. Chrome remains opt-in, not automatically added.
- Read-failure routing is covered by unit tests, not induced by corrupting credentials on a managed device. Full fresh onboarding, real-duration timers, positive live filtering/browser checks, and physical-device gates remain outstanding.

0.2.2 build fingerprints:

- Debug: `5c9d8b671516d238cd8d88370db6f84b60450c8e4ef58d1bc66f78ff39ae3404`.
- Unsigned release: `f44e167689ef3840e992280e0e9943a23fd745d6493c7a736a1f7d78c0d50773`.

### Optional daily Chrome · 0.2.1 · 2026-09-09

- Build: debug, instrumentation, unsigned release, and static checks passed; lint reports 0 errors and 18 warnings.
- Unit tests: **27 passed**. New coverage verifies Chrome remains eligible after browser discovery, eligibility does not automatically approve it, and other browsers/stores/Settings remain forbidden.
- `ChromePolicyTest.chromeApprovalLaunchAndRevocation`: **passed** (36.907 seconds) on the existing disposable `Lucy_Onboarding_Auto`. Verified a saved Chrome draft does not change the effective list; applying approval unhides Chrome, permits lock task, and actually launches Chrome; Home returns to Lucy; removal hides it and revokes lock-task access without uninstalling it. Play remains hidden, Settings remains outside lock task, installation remains blocked, and the policy reads back correctly. Original fixture app choices are restored in cleanup.
- The full `OnboardingFlowTest` now expects a selectable Chrome checkbox instead of the old setup-only classification. That revised end-to-end onboarding suite was compiled, not rerun against a fresh fixture in this revision.
- Manual UI check on 0.2.1: searched installed apps for Chrome; its checkbox was enabled and initially unchecked. Selecting it showed a saved nine-app draft and the filtering warning; deselecting restored the original eight-app draft. The effective daily list was not changed by these checkbox checks.
- No live-browser filtering pass is claimed. The network's DNS-over-TLS limitation remains; native probes do not certify Chrome traffic or its independent DNS configuration. Real browser cross-checks and physical-device release gates remain outstanding.
- Only the disposable test emulator was updated in place. No PIN reset, Device Owner removal, or user-managed emulator changes were performed.

0.2.1 build fingerprints:

- Debug: `1ba2a20b7feb776602f57415f48f1227cb58488ae0044bda81637a2e053c1c25`.
- Unsigned release: `b729da5a4843e646cb5b09456559eea17569dc3b850fbb695c7553356cdedfb0`.

### Historical onboarding revision · 0.2.0 · 2026-09-09

- Build: debug, instrumentation, unsigned release, and static checks passed; lint reports 0 errors and 18 dependency/style warnings.
- Unit tests: **24 passed**, including 5 new onboarding-gate tests.
- `OnboardingFlowTest.guidedSetupAndOfflineSafety`: **passed** on fresh hidden `Lucy_Onboarding_Auto` (`emulator-5586`, same Android 16 image). It verifies actionable setup Settings, return to the same page after reauthentication, persisted draft selection without effective-policy mutation, setup-only Chrome classification, rejection of unauthenticated/unconfirmed internet exceptions, opportunistic-mode entry, simulated expiry and startup restoration to the strict host, three separate probe results, first-activation rejection when filtering is not verified, and applying a reviewed draft to a simulated existing managed policy with Chrome/Play hidden and lock task restored. No real accounts were used.
- Visual inspection: administration overview, connection/accounts page, and app selection checked at 1080×1920; Lucy's light navigation icons are visible on a dark backing. Fixture images: `.local/qa-administration.png`, `.local/qa-connect.png`, `.local/qa-apps.png`. Test-only screenshot capture temporarily clears secure-screen protection on nonsensitive fixture pages; production protection is unchanged.
- The tests exposed and drove fixes for external Settings entering the Home task and stopping a foreground service before its first foreground notification. The rapid start/expire/start sequence now passes.
- The legacy `LauncherTest` was also attempted directly against the locked managed fixture. Android blocked the test package's bootstrap/cleanup activity (`dev.lucy.phone.test`) and teardown timed out; this run is not counted as passed. The helper now restricts that suite to the unrestricted `Lucy_Launcher`. Its historical two-test pass below is not a fresh 0.2.0 result.
- Full-duration 15/30-minute alarm behavior, Doze, real reboot/update interruption during an exception, physical/OEM behavior, actual Google/app account sign-in, and a positive live-filter pass on a compatible network remain unaccepted. Simulated expiry/startup is not equivalent to those tests.
- Existing `Lucy_Managed` credentials and selections were not reset. An earlier newly created `Lucy_Onboarding_QA` was found to contain administrator credentials; the suite stopped rather than overwrite them, and verification moved to the fresh Auto fixture.

0.2.0 build fingerprints:

- Debug: `a6d7f9cf1fda59940b3985e004af5b777b05f216d9ef2c1f343f07802328d574`.
- Unsigned release: `6b32c72cd4c3781f3052f066d69c697302282cf1aeba7f6b2c6c02cf79c98757`.

### Historical 0.1.0 baseline

| Area | Result | Evidence / remaining work |
| --- | --- | --- |
| Debug/release compilation | Passed | `bash scripts/check.sh`; unsigned release output unless production signing variables are provided |
| Static checks | Passed with warnings | Lint has no errors; dependency-update/style advisories remain. Broad package visibility is intentional for a DPC and locally documented. |
| Policy/security unit tests | 19 passed | 9 policy/serialization tests, 5 partial-failure transaction tests, 5 PIN/backoff/maintenance security tests |
| Launcher UI | 2 passed | HOME → controls → HOME → protected administrator screen; open Phone and return to Lucy using system Home |
| Device Owner | Passed in emulator | ADB assigned the actual DPC receiver as owner on the isolated managed AVD |
| Device controls and recovery | Passed in emulator | Read-back of hidden apps, enabled/unsuspended support dependencies, lock-task packages/features, HOME, user restrictions, DPC protection, and credential rotation |
| Installation restriction | Passed in emulator | An attempted ADB APK update was rejected with `User restriction prevents installing`. Debug test-owner removal was required for development updates; that is not a release maintenance bypass. |
| Maintenance interruption | Passed for simulated expiry | Actual supervised maintenance opens Settings; an expired persisted lease restores restrictions, brings Lucy HOME forward, and re-enters lock task. The real 30-minute alarm/Doze deadline still needs a hardware-duration test. |
| Warm reboot persistence | Unresolved on this emulator | Initial policy read-back passed twice; the third attempt ran before unlock. After adding explicit unlock/active-lock-task assertions, a subsequent warm reboot left the AVD offline for over 90 seconds. A cold emulator restart recovered it without disk reset. |
| Clean cold-start persistence | 3 cycles passed | `scripts/reboot-smoke.mjs --cold`: synced Android shutdown → same AVD restart → screen PIN unlock → Device Owner, HOME, restrictions, dependencies, notification permission, and active lock task verified. No disk reset or re-enrollment between these three cycles. |
| Live filtered DNS | Unverified; network failure | Initial DPC configuration returned HOST_NOT_SERVING and host TCP/853 connection failed. Later strict-DNS read-back succeeded, but `example.com` could not resolve; Android also reported the Private DNS server inaccessible. Live-filter instrumentation is skipped, not counted as passed. |
| Physical calls/SMS/emergency | Not tested | Simulated emulator telephony cannot establish carrier/emergency reliability |
| QR Setup Wizard enrollment | Not tested | Provisioning handlers and payload/checksum generator implemented; real factory-reset QR flow still required |
| Play-account installation and updates | Not tested | Maintenance implementation exists; no user Google account was added during development |
| OS/faulty-release upgrades and physical recovery | Not tested | Run on backed-up, resettable target hardware with retained release key |
| Three-day trial, battery, Samsung OEM escapes | Not tested | Hardware release gate |

The lifecycle instrumentation can pass device controls while reporting DNS unavailability. This distinction is deliberate: it exercises the platform policies and failure reporting without pretending that filtering is active. The separate live DNS test must pass on a network supporting the provider before release.

Historical 0.1.0 build SHA-256 fingerprints (not the onboarding revision):

- Debug APK: `71f07136c0be5a0a7057eb43327f6323fd9f6db92da623ba4d64fc91aef3d8d5`.
- Unsigned release APK: `1c603824b2d2a878feea3c62a126efac864b274c686ea587405af2369b8ac89a`.

## Package baseline

HOME entries are discovered from installed apps and default roles. The emulator includes Phone, Messages, Contacts, Camera, Photos, Maps, Calendar, and Clock. Essential package removal is rejected.

For new setup, only discovered essential apps are initially selected. Optional Chrome/Calendar/Authenticator/Slack/ChatGPT suggestions require explicit selection; existing effective selections survive upgrade. A stored app draft is distinct from the active policy. Chrome eligibility was added in 0.2.1.

Required infrastructure includes Google Play services, input methods, the permission controller, telephony/Telecom, media/contact/download providers, document picker, WebView, carrier components, and cell-broadcast/emergency dependencies. These are kept enabled independently of HOME visibility. Only explicit activity dependencies enter lock task.

An early inventory iteration included disabled launcher aliases from Google Play services among blocked apps. Emulator testing caught this; the critical-dependency baseline now prevents hiding them. Regression assertions verify Play services remains enabled.

## Final hardware checklist

For each exact Pixel/Samsung build, record results and evidence for every row in the implementation plan: QR setup, cross-app/notification/Settings escapes, real phone functions, work-app sign-in expiry, filtering on Wi-Fi/cellular/IPv6, network outage, three reboots, app/DPC updates, process death/maintenance expiry, credential recovery, planned and physical reset, and three days of normal use.

Release requires all applicable rows to pass on the claimed device baseline. Neither a passing emulator suite nor debug ADB behavior establishes the release-build security claim.
# Samsung supervised setup regression — 2026-11-02

Lucy 0.3.5 installed on SM-S918U. Opening supervised setup previously repeatedly destroyed HOME when it stopped a child task while an older root remained locked. Clearing the entire allowlist ended that loop but could still destroy the current root activity. The transition now stops the current task once after persisting the maintenance lease, then clears the allowlist to exit any remaining managed tasks; lifecycle enforcement never repeatedly stops HOME. If Android destroys the activity, Lucy returns automatically after setup completes.

Physical verification: the user confirmed the setup page stayed visible. ADB confirmed the same HOME ActivityRecord/task (2616) remained foreground before and after entry, lock task changed from LOCKED to NONE, the maintenance lease was present, policy errors were empty, and the crash buffer was empty. Build, unit tests, lint, debug/test APK compilation, and release assembly passed. The new disposable-emulator maintenance assertion compiled but was not executed on the personal Samsung. Expiry/reboot and the fallback for pre-existing nested tasks were not re-tested in this verification.

