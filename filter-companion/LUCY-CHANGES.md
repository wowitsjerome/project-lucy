# Lucy companion modifications

Based on IngoZenz/personaldnsfilter commit
94182b2b17b2f0f392e5d973d2076fb3b32abfde.

Changes dated 2026-11-02:
- Bundle HaGeZi NSFW and its GPL-3.0 license; select it as the only downloaded
  blocklist, refreshed daily.
- Add a reserved test domain and all subdomains as a local boundary-check fixture.
- Use Cloudflare Families DNS-over-HTTPS upstream, disable query logging.
- Route known network DNS and common hardcoded DNS endpoints through filter.
- Remove notification pause action, pause broadcast registration, and quick tile.
- Upgrade build tooling to locally supported AGP/Gradle.
- Add tests for domain-boundary behavior and bundled-list policy.
- Migrate upgrades to Lucy's required resolver, autostart, filter, bundled-list,
  and mandatory-host policy while preserving administrator-added host rules.

Source license: GPL-2.0-or-later; see LICENSE and source headers. List license:
GPL-3.0; see app/src/main/assets/HAGEZI-LICENSE.txt. This modified source tree,
including build files and assets, is the corresponding source for this build.

This companion is DNS filtering, not a full traffic firewall. Custom encrypted
DNS/direct-IP traffic can bypass it; a stopped filter can fail open. Always-on
without lockdown restarts the companion. Lucy owns protection and daily UI
access. Do not present category fixtures as proof of complete content coverage.
