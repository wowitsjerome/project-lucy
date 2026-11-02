# Local adult-content filtering

Lucy 0.3.0 supports a companion build of personalDNSfilter (`dnsfilter.android`).
The companion bundles HaGeZi NSFW, updates it daily over HTTPS, and adds `blocked.lucy.test`
as an administrator override including subdomains. Cloudflare Families is the
encrypted upstream resolver. Ordinary DNS queries are evaluated locally first.

The companion is a modified GPL-2.0-or-later project. Its source and license must
accompany distribution. The bundled HaGeZi data includes its GPL-3.0 license.
The companion source is kept separately from Lucy.

Install only during authenticated supervised maintenance. Install Lucy first,
then the companion during a new maintenance session; the old Lucy version can
otherwise hide the new package as unapproved. Lucy discovers the installed
companion as a support dependency and configures always-on VPN, uninstall and
force-stop protection. Reapply/test filtering to enable it. Notification pause
and quick-settings toggles are removed from this companion build.

Android Private DNS is switched off while this local DNS VPN is selected so it
cannot bypass the local rules. This does not mean unencrypted upstream DNS:
the companion uses pinned-address Cloudflare Families DNS-over-HTTPS endpoints.
The existing temporary-unfiltered-internet action is rejected in local mode.

This is a DNS-only split tunnel, not a full traffic firewall. Always-on is used
without VPN lockdown because lockdown would block ordinary traffic outside the
DNS routes. A stopped filter can therefore fail open until restarted. Chrome's
DnsOverHttpsMode managed setting is set to off, but Chrome policy acceptance
must be verified on the actual installed Chrome version. Other applications
with custom DoH/direct-IP traffic can bypass DNS filtering. These limits must
not be presented as full device-wide content enforcement.

Acceptance must include: normal HTTPS; both Cloudflare fixtures; blocked.lucy.test;
sub.blocked.lucy.test; a HaGeZi-listed domain; browser verification with cold connections;
Wi-Fi/cellular transitions; reboot; companion process restart; blocked access to
companion controls in daily lock task; and retained administrator recovery.
Unit/build checks alone do not establish these physical-device results.
