# Security

## Threat model
| Asset | Threat | Control |
|---|---|---|
| minicam password | phishing, interception, storage leak | Never stored; sent only to the configured host; `https` required off-LAN |
| `minicam_session` cookie | theft from device storage or logs | Keystore/Keychain only (`WhenUnlockedThisDeviceOnly`), never logged, cleared on logout/401 |
| CF Access token | interception of the handoff redirect | Random `state` checked on callback; token in URL fragment; it is only the first factor (a minicam session is still required) |
| Camera feed | sniffing on untrusted networks | `http` allowed only to LAN hosts; WAN is `https`/`wss` through the tunnel |
| Stream token | reuse | Issued per camera, 30 s TTL by the server; never persisted |

## Transport rules
- `https://` hosts: always allowed. `http://` hosts: only private IPv4 (10/8, 172.16/12,
  192.168/16), loopback, and `*.local` names. Enforced in `ServerConfig` on both platforms.
- Android: cleartext is enabled in `network_security_config.xml` because domain-based configs can't
  express "private IPs"; `ServerConfig` is the gate. iOS: `NSAllowsLocalNetworking` only (no
  arbitrary loads).
- Known limitation inherited from minicam: on LAN the password and cookie travel in clear text.
  Users on shared Wi-Fi should use the WAN hostname. Tracked in KNOWN_ISSUES.md.

## Cloudflare Access handoff
See [ADR 0002](adr/0002-cloudflare-access-handoff.md). Custom URL schemes can be claimed by other
apps on Android. A hijacked callback leaks the Access token only; minicam still requires the
password session, and the server pins the redirect target (no open redirect).

## Admin screens (ADR 0004)
- Shown only to admins, but authorization is the server's `require_admin`; the app never relies
  on hiding a screen.
- Camera passwords and new-user passwords exist only in the open form: never prefilled (the
  server redacts them), never in saved state, logs or preferences.
- Deleting a camera or a user always asks for confirmation first.
- Text from the remote model catalog is rendered as plain text. Model URLs must be `https`
  (checked in the app and by the server); the server, not the app, downloads them.

## Recordings (ADR 0005)
- Who sees a clip is the server's decision (`can_view`, WAN gate); the app never filters clips.
- Deleting a clip always asks for confirmation first.
- The session cookie / Access token are sent only by the app's own clients (OkHttp data source,
  AVURLAsset, URLSession); never handed to `DownloadManager`, a browser or another app.
- Downloads go only where the user picks (Android SAF) or to the app's temp dir, removed after
  the share sheet closes (iOS).

## Live fMP4 and audio (ADR 0006)
- fMP4 parsers treat server bytes as untrusted: every box and sample size is checked against the
  message; a bad message is dropped (fallback to MJPEG), never a crash or an out-of-bounds read.
- Audio is never requested unless the user taps the speaker button in this view; the server
  enforces `can_listen`. Nothing is cached or recorded on the device.
- Admin upserts always send `can_listen`, so editing other flags never changes it (KI-9).

## Bandwidth / cost note
MJPEG at 720p/15 fps is ~1 MB/s (~4 GB/hour per viewer); fMP4 is the camera's own bitrate
(~150 kbit/s for a sub stream). The app must close streams when not
visible, and each camera open streams for at most 30 s (spec 011). A data-saver mode is planned
(ROADMAP M3).

## Per-feature checklist
- [ ] Input validation: server URL, camera names URL-encoded in paths
- [ ] AuthN/AuthZ: handles 401 (logout), 403 (Access/WAN gate), 404 (no permission) distinctly
- [ ] Secrets: nothing sensitive in logs, crash output, screenshots of login fields, or backups
  (`android:allowBackup="false"`)
- [ ] Transport: rules above hold; no new hosts contacted
- [ ] Dependencies: any new one follows DEPENDENCIES.md

## CI/CD
Workflows default to `contents: read`; only the release publish job can write. The Android
keystore lives only in repo secrets and `$RUNNER_TEMP` during the build; fork PRs get no secrets.
Third-party actions are pinned by commit SHA (ADR 0003).
