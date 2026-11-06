# Onboarding and everyday administration

Lucy has two audiences: the person using a quiet daily phone, and the trusted administrator who prepares and maintains it. Do not hand over the phone while setup, maintenance, temporary internet, or verification is incomplete.

## Before adding accounts

Choose **launcher preview** to explore the interface without restrictions. Choose **Device Owner enrollment** for an actual managed phone; enroll before adding Google accounts or Android profiles. A phone already configured for personal use generally requires a backed-up factory reset for managed enrollment. The emulator helpers never reset a personal phone.

Lucy supports signing into Google and individual apps, including selecting an account or workspace inside an app. It does not support extra Android users, corporate work-profile enrollment, or mandatory corporate VPNs in this version. Account credentials stay in the app/Android sign-in screen; Lucy does not collect them.

Create a separate administrator PIN, save the one-time recovery code offline, and set an Android screen-lock PIN. These are different credentials; neither has a default. Allow Lucy under **Alarms & reminders** so timed supervision can expire reliably.

On startup, **Loading administration…** stays disabled until Lucy reads the saved setup. An existing administrator is sent to the existing-PIN screen, never new-PIN setup. A read failure or a managed device with missing credentials shows **Administrator access unavailable** with a retry option; it does not reset credentials. New-PIN setup is offered only after confirming there is no administrator and no active managed policy.

## Everyday Home choices versus administration

**Phone controls** does not require the administrator PIN. It brings everyday sound, display, Wi-Fi, and Home-screen choices together. **Choose Home screen apps** lets the user show or hide apps that are already approved, while required phone functions remain visible. Hiding an app removes its Lucy Home button; it does not uninstall the app, erase its data, or revoke its approved access.

**Administration** remains PIN-protected. It controls the security allowlist, installation and updates, accounts and Android settings, filtering, maintenance, diagnostics, and recovery. Newly approved apps start visible on Home. Removing approval also removes the app from Home and daily lock-task access. Existing Home visibility choices are preserved when other allowlist changes are applied.

## The four onboarding pages

| Page | What to do | Completion evidence |
| --- | --- | --- |
| **1 · Connect & accounts** | Connect Wi-Fi/cellular. Open Accounts & sync, Settings, or Chrome directly. Sign into Google before using Play. | Normal internet works; the correct accounts are present. |
| **2 · Allow & set up apps** | Keep phone essentials. Install needed apps in Play, refresh the list, approve daily apps, and use each app's **Set up** button to sign in and grant permissions. | Confirm the allowed apps, accounts, and permissions checklist. |
| **3 · Test filtering** | Run Lucy's built-in checks. Optionally cross-check the harmless test links in a supervised browser. | Normal DNS + HTTPS access and both category blocks pass on the current network. |
| **4 · Review & finish** | Review the exact daily list, saved recovery code, and visible Home/Back navigation. Enable daily mode only when prerequisites pass. | Device policy is read back, filters pass, and core phone tasks work. |

There is no lockdown as a side effect of saving an app choice. Choices are persisted as a **draft**; leaving Lucy for sign-in does not lose them. Administration locks when you leave, and returns to the same page after reauthentication while the activity remains alive. Process death retains the draft but can return you to the overview. Reopening the app ends existing timed exceptions for safety.

### Which apps belong on the phone?

Start with the installed default apps for calls, messaging, contacts, camera, photos, maps, and alarms. Lucy discovers these roles rather than assuming every phone has the same packages. These entries are required. Verify the actual chosen defaults on each supported device; missing role handlers are a device-baseline issue, not proof that all phone functions exist.

Add an optional app only for a named need. The UI offers Chrome, Calendar, Authenticator, Slack, and ChatGPT as starting points, not automatic approvals or security certifications. No optional suggestion is automatically selected on a new phone. Existing saved app selections are retained on upgrade.

For each candidate:

1. Install it from Play during setup/maintenance.
2. Approve it in the administrator allowlist draft; installation alone does not approve it.
3. Open **Set up**, sign into the intended account/workspace, and allow only necessary permissions.
4. Test normal tasks, notifications, links, file/photo pickers, and returning Home. Content inside an app is not screened by domain filtering; exclude apps that undermine the intended boundaries.
5. Confirm the checklist and apply the draft. Retest the app in daily mode: access during setup alone does not establish compatibility with lockdown. Reauthentication later uses the same supervised session flow.

**To add Chrome:** open **Allow & set up apps**, search for Chrome, and select its checkbox. If missing, use **Find in Play**, install it during supervised setup, then refresh. Complete the app checklist and use **Review & finish → Apply draft & return to daily mode** (or **Save launcher preview** in preview mode). Chrome is approved and initially appears on Home. **Phone controls → Choose Home screen apps** may hide its button without revoking approval; deselecting it in Administration and applying the draft revokes daily access without uninstalling Chrome or erasing its sign-ins.

Chrome is the only selectable general-purpose browser in this version (`com.android.chrome`; Beta/Dev/Canary are not included). Other browsers, stores, and unrestricted Android Settings remain **setup tools**, with dedicated buttons instead of daily-app checkboxes. Chrome's own settings remain accessible when Chrome is approved. Infrastructure such as Play services, keyboards, WebView, and permission providers remains separate from visible apps.

Selecting Chrome does not disable Lucy's filtered Private DNS or relax installation restrictions. However, Lucy does not manage Chrome's own Secure DNS settings: a [custom Secure DNS provider](https://support.google.com/chrome/answer/10468685?co=GENIE.Platform%3DAndroid&hl=en) can use a different resolver. Domain filtering also cannot inspect individual pages or content inside allowed sites. Review Chrome's DNS configuration and run the browser cross-check below before handoff; a passing Lucy check alone is not proof that Chrome browsing is filtered. Exclude Chrome if these limits do not fit the person's needs.

### Internet when the filtered resolver is unavailable

Filtering uses `family.cloudflare-dns.com` over TLS port 853. A connected Wi-Fi symbol does not guarantee that this service or normal internet works. The development host has previously blocked this path.

In **Connect & accounts**, an authenticated administrator can explicitly confirm **Temporary internet · 15 minutes**. This uses the network's resolver and removes Lucy's domain filtering during the exception. It does not fix an offline network. Requirements:

- Device Owner mode and timer permission; on an already managed phone, start supervised maintenance first.
- A clear confirmation warning, on-screen unfiltered state, persistent deadline, and foreground supervision notification.
- The deadline is at most 15 minutes and never later than the maintenance deadline. It ends on explicit filtering test, session completion, process restart, reboot, or expiry.
- Strict hostname mode is restored and read back even if the provider is offline. Connectivity can stop again; the app never silently converts that into a pass. A failed platform restoration retains its journal obligation and error for retry.

Offline restoration uses Device Owner `setGlobalSetting` for the strict DNS keys allowlisted by [AOSP Android 11](https://github.com/aosp-mirror/platform_frameworks_base/blob/android11-release/services/devicepolicy/java/com/android/server/devicepolicy/DevicePolicyManagerService.java) and [Android 16](https://github.com/aosp-mirror/platform_frameworks_base/blob/android16-release/services/devicepolicy/java/com/android/server/devicepolicy/DevicePolicyManagerService.java). The convenience hostname setter checks connectivity before writing the mode, so it cannot by itself guarantee offline restoration. Lucy checks this capability before opening an exception. OEM behavior and real-duration Doze/reboot tests remain hardware gates; simulated expiry is not a 15-minute timing certification.

First-time daily activation requires a passing live filtering check. You can prepare accounts using temporary internet, but must change to a compatible network before completing managed onboarding. Launcher preview is available without claiming enforcement. This version has no alternative VPN or DNS-over-HTTPS enforcement backend.

### Testing without Chrome

**Enable filtering & run checks** restores strict DNS and reports three independent results:

- Normal internet: `example.com` resolves normally and an HTTPS request succeeds.
- Adult-category blocking: `nudity.testcategory.com` resolves to the provider's blocking address.
- Malware blocking: `malware.testcategory.com` resolves to the provider's blocking address.

If normal internet fails, category results are **not assessed**, not passed. A result is valid for five minutes, the same active network, and the same strict DNS setting. The two blocked domains are [Cloudflare's harmless test fixtures](https://developers.cloudflare.com/1.1.1.1/setup/).

For an optional browser cross-check, use the three named link buttons during supervised setup with temporary internet off. Disable Chrome's custom Secure DNS for that test; application-managed resolvers can bypass Android's resolver. An error page alone proves nothing unless the allowed control loads. Do not test using actual harmful content.

## Returning to administration later

Open **Administration → Open supervised setup**, authenticate, and confirm physical custody. A 30-minute session exposes Settings, Play, and browser tools. Filtered DNS stays active unless you separately confirm temporary internet. The two-minute administrator UI session and the longer maintenance lease are separate: leaving for sign-in locks the former, not the latter.

Install/update/sign in → refresh/approve apps → confirm their setup → test filtering → **Apply draft & return to daily mode**. Applying a draft can end maintenance directly; there is no need to hide an app again before it can be approved.

**End session without applying draft** immediately restores the existing daily list and strict DNS. Draft choices remain saved. This safety action remains available even if filtering cannot pass. In that case the device stays **Attention required** and should not be handed over. Expiry and reboot restore restrictions too.

To upgrade the existing managed emulator without erasing its PIN: use the installed version's Administration → Start supervised maintenance, then install the new debug APK with `node scripts/device.mjs install emulator-5582`. The same signing key preserves app data; replacement ends timed sessions and restores policy. Do not remove Device Owner or clear app storage as a routine upgrade step.

If Chrome is visible but its checkbox is disabled on the old 0.1.0 administration screen, update Lucy; daily Chrome selection was added in 0.2.1. The 0.2.2 build includes that option and the startup PIN-screen fix. Building an APK alone does not update an already-running emulator.

## Navigation and final handoff

Use three-button navigation on the current Android 16 emulator. Its gesture route invokes Pixel Launcher, which is intentionally not a daily allowed app. Lucy paints a dark navigation-bar background with light buttons. Test an actual visible Home-button tap from another app; a keyboard Home event alone is insufficient evidence.

Before handing over, verify calls/messages, contacts, camera/photos, maps, alarms, notifications, required app sign-ins, unapproved-browser/Settings closure, and filtering. If Chrome is approved, verify opening it from Home, returning Home, the harmless browser cross-check, hiding/showing it from **Phone controls**, and revoking its approval in Administration. Physical telephony, emergency access, OEM behavior, QR enrollment, real account upgrades, and a three-day trial are still mandatory release gates. See [acceptance.md](acceptance.md).
