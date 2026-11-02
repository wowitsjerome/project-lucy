# Minimalist Android Phone MVP

Implementation status (2026-09-08): the Kotlin/Compose launcher and local DPC are implemented, with isolated launcher/managed emulators, automated checks, and provisioning/recovery helpers. Start with [README.md](README.md); use [docs/acceptance.md](docs/acceptance.md) as the source of truth for verified behavior. Live filtering and physical-device release gates are not yet accepted. The plan below remains the acceptance contract, not a claim that hardware validation is complete.

Onboarding revision: [docs/onboarding.md](docs/onboarding.md) defines the user-facing flow, app-selection process, account setup, and recovery from unavailable filtered internet. Setup must precede lockdown; a saved app draft is not an applied policy.

## 1. Scope and decisions

Build a personal-use phone on stock Android with a text launcher, approved apps, domain filtering, and restrictions controlled by a trusted local administrator.

| Decision | V1 implementation |
| --- | --- |
| Architecture | One Kotlin/Jetpack Compose APK: launcher + Device Policy Controller (DPC), provisioned as Device Owner |
| Enforcement | Multi-app lock-task mode and persistent HOME activity |
| Platform | Android 11/API 30 minimum; currently supported stock firmware |
| Hardware | Pixel reference device and Samsung Galaxy compatibility device; Phase 0 records exact models, builds, security patches, and carriers |
| Filtering | Cloudflare Families Private DNS; Chrome is an optional daily app with explicit domain-filtering limitations |
| Administration | Separate local administrator PIN, held by a trusted person; self-held PIN offers weaker accountability |
| Installation | Supervised Google Play maintenance; signed local installation for our own APK |

Required daily functions: calls, messaging, contacts, camera/photo viewing, navigation, and alarms. Optional app candidates: Chrome, Slack, ChatGPT, Authenticator, and Calendar. Approve each only after testing its dependencies and normal flows; record excluded optional apps in the acceptance results.

Threat boundary: resist ordinary UI access, sideloading, alternate launchers, network changes, and reboot. Root, firmware replacement, recovery-mode wiping, and access with administrator credentials are outside this guarantee. Settings-based reset must be blocked; management persistence after a physical wipe is not promised.

Out of scope: custom ROM, remote administration, product accounts/billing, proprietary app store or filter, VPN implementation, and commercial distribution. Existing Google/app accounts are permitted. Required corporate work profiles or corporate VPNs are V1 compatibility exclusions.

## 2. Structure and policy model

Keep four packages in one Android project: `launcher` (HOME/UI), `policy` (provisioning/enforcement), `filtering` (DNS/health probes), and `configuration` (storage/credentials/maintenance).

Store a versioned policy in app-private storage:

| Field | Purpose |
| --- | --- |
| `schemaVersion`, `revision`, `deviceBaselineId` | Policy format and validated device baseline |
| `visibleApps` | Ordered package/activity IDs displayed on HOME |
| `supportPackages` | Required providers/services kept enabled, each with a reason |
| `lockTaskPackages` | Explicit packages allowed to present daily-use activities, including the launcher |
| `blockedPackages` | Unapproved apps and their hide/suspend action |
| `privateDnsHost` | `family.cloudflare-dns.com` |
| `lastVerifiedRevision`, `policyErrors` | Applied-policy verification and failures |

Resolve actual package IDs and role holders on each device. Never disable every package absent from the launcher list. Preserve the DPC, input method, telephony stack, permission controller, WebView, providers, and validated OEM dependencies. A support package does not automatically enter the lock-task list.

Validate changes, retain a known-good revision, apply policies, and read back results. Reject removal of essential packages. Reconcile on startup, boot/unlock, package changes, and maintenance completion. On partial failure, attempt the known-good policy and show **Attention required**; preserve calling and administrator recovery.

Expose **Ready**, **Maintenance**, and **Attention required** states. Display apps from the verified effective policy; saving configuration alone must never produce **Ready**.

Before activation, expose **Setup incomplete** or **Launcher preview**. Persist app-selection drafts and checklist acknowledgments independently from effective policy. An explicit **Unfiltered · setup only** state overrides all ready/maintenance wording during a temporary internet exception. Keep operational diagnostics separate from the main administrator tasks.

## 3. Enforcement

### Launcher and system UI

1. Register HOME and set it using `addPersistentPreferredActivity`.
2. Configure `setLockTaskPackages`; enter lock task after unlock and launch approved apps with lock-task-enabled activity options.
3. Enable HOME, notifications, system information, keyguard, and power actions. Disable Overview/Recents. Include `LOCK_TASK_FEATURE_BLOCK_ACTIVITY_START_IN_TASK` to block unapproved activities entering existing tasks; this sets the API 30 floor.
4. Validate incoming-call UI, notification actions, permission prompts, pickers, and external links. Permit only documented dependencies; reject exceptions exposing unrestricted Android Settings or unapproved browsers. Approving Chrome explicitly permits browsing and Chrome's own settings, subject to the filtering limitations below.
5. Build text app buttons, date/time, basic brightness/volume controls, and admin entry. Omit drawers, widgets, feeds, ads, and badges. Wi-Fi setup, Bluetooth pairing, and account changes require supervised maintenance in V1.

Use three-button navigation on the current Android 16 emulator; gesture-based Home/Overview invokes the stock launcher and is not accepted. Paint a high-contrast navigation-bar backing and test an actual button tap from an app, not only a keyboard Home event. Validate the final navigation mode independently on each physical baseline.

Preserve screen lock and emergency access. Lock task changes normal system UI behavior, so physical-device testing is a prerequisite. References: [lock-task behavior](https://developer.android.com/work/dpc/dedicated-devices/lock-task-mode), [HOME configuration](https://developer.android.com/work/dpc/dedicated-devices/cookbook).

### Daily restrictions

Apply through `DevicePolicyManager.addUserRestriction`:

| Restrictions | Intended result |
| --- | --- |
| `DISALLOW_INSTALL_APPS`, `DISALLOW_INSTALL_UNKNOWN_SOURCES_GLOBALLY` | Block installations and unknown-source settings |
| `DISALLOW_ADD_USER`, `DISALLOW_USER_SWITCH` | Block additional-user escape routes |
| `DISALLOW_CONFIG_PRIVATE_DNS`, `DISALLOW_CONFIG_VPN` | Protect network policy |
| `DISALLOW_DEBUGGING_FEATURES`, `DISALLOW_SAFE_BOOT` | Block debugging and safe-mode routes |
| `DISALLOW_FACTORY_RESET` | Block reset through Settings |

`DISALLOW_INSTALL_APPS` also restricts owner-initiated installation; clear it during supervised maintenance. See [Android user restrictions](https://developer.android.com/reference/android/os/UserManager).

Hide unapproved apps (including Chrome unless selected), other browsers, and store UIs with `setApplicationHidden`; use suspension only when specified by the device baseline. Chrome (`com.android.chrome`) is selectable but never automatically approved; selection includes it in the launcher and lock-task list. Removing approval hides it again without uninstalling it. Inspect failures because some essential packages cannot be suspended. Protect approved apps from uninstall and the DPC from user force-stop/data clearing. See [DevicePolicyManager APIs](https://developer.android.com/reference/android/app/admin/DevicePolicyManager).

Keep the Settings package enabled for system dependencies but outside the daily lock-task list. Test reachable Settings paths, OEM stores/assistants, Secure Folder/profile features, accessibility services, and overlays. Constrain demonstrated escapes; unresolved escapes block device support.

## 4. Provisioning and app maintenance

### Initial setup

1. Use a resettable test phone; record recovery instructions and back up user data before reset.
2. For the development spike, install the DPC and use ADB Device Owner provisioning before adding accounts, users, or work profiles. Record exact package/component names and commands.
3. For daily use, implement factory-reset QR enrollment with an HTTPS APK download and checksum. On Android 12+, implement `ACTION_GET_PROVISIONING_MODE` and `ACTION_ADMIN_POLICY_COMPLIANCE`. Verify Device Owner status. References: [ADB setup](https://developer.android.com/work/guide), [Android 12 provisioning](https://developer.android.com/work/versions/android-12).
4. Create the administrator PIN/recovery code and allow the maintenance timer. **Connect & accounts** exposes Wi-Fi, Accounts & sync, Settings, and browser buttons before lockdown. Sign into Google and individual app accounts; corporate work profiles remain excluded.
5. **Choose & set up apps** starts with required phone functions, shows optional suggestions without auto-approving them, and separates installed apps from setup-only tools. Install via Play, refresh, save the daily-use draft, and open each selected app for sign-in/permissions. Confirm the selected apps were reviewed.
6. **Test filtering** restores strict DNS and runs normal DNS + HTTPS and two harmless category probes inside Lucy. Provide optional browser test links during supervision. An offline control is inconclusive, never a blocking success.
7. **Review & finish** shows the exact daily list and missing prerequisites. Require a screen lock, timer, saved recovery-code acknowledgment, reviewed apps, navigation acknowledgment, and a live filter pass on the same network before first activation. Failed checks leave setup unlocked. Apply restrictions, read back, and test actual phone/app flows before handoff.

### Installation and updates

Approving an installed app means enabling it and updating policy. A new app, authentication repair, or update requires maintenance:

1. Authenticate and persist **Maintenance** state. The administrator retains physical custody while restrictions are relaxed.
2. Leave lock task and relax only necessary restrictions; expose Settings/accounts/Play/browser as explicit setup tools. Keep filtered DNS enabled by default; an independently confirmed, timed internet exception is permitted as described below.
3. Install/update third-party apps through Play. Update our DPC using an APK signed with the retained release key; verify credentials and policy survive. Do not redistribute third-party Play APKs.
4. Save/review the new app draft while maintenance is active. **Apply draft & return to daily mode** applies it and closes setup tools in one flow. **End session without applying draft** restores the existing daily list immediately and retains the draft. Run policy/phone/filter smoke tests; interrupted verification remains **Attention required**.
5. Restore daily restrictions on expiry, reboot, and explicit completion. Persist pending restoration across process death and DPC updates. Return the device only when **Ready**.

Perform supervised app maintenance weekly and promptly for urgent fixes. Keep Android security updates enabled and reverify after OS updates. Do not assume background Play updates work while installs are blocked. Back up the release signing key and test recovery from a faulty DPC upgrade.

The MVP does not depend on a managed-Play backend. New custom DPC registration for the Google Play EMM API is restricted; commercial distribution requires a separate feasibility decision. References: [installation guidance](https://developer.android.com/work/dpc/dedicated-devices/cookbook), [DPC registration](https://developer.android.com/work/dpc/build-dpc).

## 5. Filtering contract

Set strict hostname mode to `family.cloudflare-dns.com`, read back host/mode, and enforce `DISALLOW_CONFIG_PRIVATE_DNS` in daily mode. Offline restoration uses Device Owner's AOSP-allowlisted `private_dns_specifier` and `private_dns_mode` global settings: the convenience hostname setter checks network reachability before storing the mode. Verify offline restoration support before allowing a temporary exception; see the implementation rationale and source links in [onboarding.md](docs/onboarding.md). References: [Private DNS API](https://developer.android.com/reference/android/app/admin/DevicePolicyManager#setGlobalPrivateDnsModeSpecifiedHost(android.content.ComponentName,%20java.lang.String)), [provider setup](https://developers.cloudflare.com/1.1.1.1/setup/android/).

V1 blocks provider-classified domains for requests using Android's configured resolver. It does not inspect HTTPS pages, media in allowed apps, direct-IP traffic, or application-managed DNS. Chrome may be explicitly approved for daily use; other general-purpose browsers remain unavailable. Show filtering limitations next to Chrome's selection and again at review. Lucy does not lock Chrome's own Secure DNS configuration; inspect it and cross-check the harmless test sites in Chrome before handoff. A native filtering pass does not certify browser traffic. Test each app's embedded browsing/networking behavior.

Verify with harmless `nudity.testcategory.com` and `malware.testcategory.com` probes plus an allowed-domain control. Test Wi-Fi, cellular, IPv4/IPv6 where available, switching networks, and reboot. See [provider test endpoints](https://developers.cloudflare.com/1.1.1.1/setup/#test-1111-for-families).

On resolver outage, retain the configured host and report degraded connectivity; never silently switch to unfiltered DNS. Cached/direct-IP connections may still work: this is not an all-traffic kill switch. Captive portals incompatible with this policy are unsupported in V1; use cellular or another network.

Exception: an authenticated administrator retaining physical custody may explicitly confirm **Temporary internet** during setup or maintenance. Persist an at-most-15-minute deadline, capped by the maintenance deadline, before switching to opportunistic network DNS. Warn that filtering is absent; revoke earlier filter evidence. Restore strict DNS on expiry, explicit filtering test/completion, process restart, update, and boot, even when the provider is unreachable. Keep a failed-restoration journal for retry. Never activate a new daily policy using an unfiltered or stale test result. A compatible network is still required to finish onboarding.

Record alternate DNS/DoH, direct-IP, proxy, and embedded-content coverage limits. If an approved app provides a repeatable route to prohibited websites, block it or revise filtering before acceptance. A later always-on VPN spike must demonstrate improvement; VPN routing alone does not classify HTTPS content.

## 6. Administrator security and recovery

- Use a separate PIN of at least eight digits and a salted password-derived verifier. Exclude credentials/policy from Android backup; never log secrets.
- After five failed attempts, delay 30 seconds; double subsequent delays up to 15 minutes. Persist failure state so reboot/clock changes cannot reset the penalty. Lock admin UI after two minutes of inactivity.
- Generate a single-use recovery code with at least 128 bits of randomness; store only its verifier. The administrator stores the code offline. Recovery rotates the PIN and code.
- Authenticate every policy mutation entry point. Keep internal components unexported except required HOME/provisioning components, protected as appropriate.
- Limit supervised maintenance to 30 minutes; extension requires authentication. Restore restrictions on expiry/reboot/completion and retry pending restoration after crashes.
- Log policy revisions, maintenance transitions, and failures locally without browsing history or message content.
- Test physical reset/reprovisioning for a broken DPC or lost credentials, including data loss and account/Factory Reset Protection prerequisites. A recovery code only helps while the DPC works.
- Planned decommissioning requires authenticated maintenance, backup, account/FRP preparation, and explicitly confirmed factory reset; verify unmanaged setup afterward.

## 7. Build sequence and estimates

Engineering days for one experienced Android developer with both devices available; hardware delays, major OEM failures, and a VPN redesign are excluded.

| Phase | Work and exit evidence | Days |
| --- | --- | --- |
| 0 — Feasibility | Test DPC/minimal harness on both devices. Record builds/packages; demonstrate Device Owner, lock task, calls/SMS, DNS, and recovery. Resolve failed core assumptions before UI work. | 1–2 |
| 1 — Reference POC | Combined APK, fixed policy, text HOME, package controls, DNS, and ADB setup. Demonstrate basic phone functions and blocked UI escapes on Pixel. | 2–3 |
| 2 — Administration | Versioned policy, PIN/recovery, supervised maintenance, QR enrollment, status/read-back, interrupted-session restoration, signed DPC upgrade. | 3–5 |
| 3 — Hardening | Complete acceptance on both devices, fix failures, and run a three-day daily-use trial. Publish supported baselines and runbooks. | 3–5 |

Reference POC: Phases 0–1, **3–5 engineering days**. Personal MVP: all phases, **9–15 engineering days**, roughly two to three workweeks plus any non-overlapping trial time. Samsung compatibility has its own pass/fail result; Pixel success does not establish Samsung support.

## 8. Acceptance and release gate

Record expected/actual results, device/build/carrier, policy revision, app versions, and evidence in `docs/acceptance.md`. Use controlled non-explicit bypass fixtures. Automate policy validation, partial-failure recovery, PIN throttling, and maintenance restoration; test OS/OEM behavior on physical devices.

| Test | Pass condition |
| --- | --- |
| Provisioning | Fresh reset reaches verified Device Owner and **Ready** through documented QR setup without ADB. |
| App access | HOME, Recents, notifications, share/open-with intents, links, and lock-screen shortcuts expose only approved apps and documented system flows. |
| Enforcement | Store/APK installation, alternate launcher, Settings links, user/profile creation, debugging, safe boot, and DPC uninstall/clear/force-stop cannot escape restrictions. |
| Phone functions | Incoming/outgoing calls, locked-screen answering, SMS/MMS, contacts, camera/photo sharing, navigation, alarms, and notifications work. Verify emergency dialer access without an uncoordinated live emergency call. |
| Work apps | Approved app flows work after lockdown; sign-in expiry has tested recovery; embedded web access exposes no unapproved browsing route. |
| Filtering | Blocked probes fail and allowed controls succeed on supported networks; DNS/VPN settings are protected; coverage exceptions are documented. |
| Network failure | Resolver outage, airplane mode, SIM removal, network switching, and IPv6 preserve policy without silently disabling filtering; admin recovery remains reachable. |
| Persistence | Three reboot/unlock cycles and a DPC process restart retain policy; crashes never restore unrestricted HOME or unauthenticated administration. |
| Maintenance | Add/remove an optional app, update a Play app and the signed DPC, then verify lockdown. Expiry, process death, and reboot restore restrictions; incomplete verification remains visible. |
| Onboarding | Connect, install, sign in, grant permissions, and persist/review a draft before lockdown. Settings/browser buttons work during setup, the draft survives reauthentication, and failed filtering blocks first activation without locking out setup tools. |
| Temporary internet | Requires PIN, explicit confirmation, owner, and timer; displays unfiltered state, cannot produce a filter pass, and restores strict DNS on simulated/real expiry, network outage, process death, update, and reboot. Test the actual time limit under Doze on hardware. |
| Credentials | PIN throttling survives restart/clock changes; recovery code rotates credentials; essential packages cannot be removed. |
| Recovery | Settings reset is blocked; documented decommissioning and physical recovery/reprovisioning succeed on the test device. |
| Trial | Three consecutive days without management-caused loss of calls/messages/alarms, an escape route, or unexplained policy loss. Record battery use and operational friction. |

Release only for baselines passing all applicable tests, with no unresolved enforcement or essential-phone failures. Deliver the signed APK, source/build instructions, package baseline, acceptance results, and `docs/provisioning-and-recovery.md`. All hardware support claims remain pending until these results exist.
