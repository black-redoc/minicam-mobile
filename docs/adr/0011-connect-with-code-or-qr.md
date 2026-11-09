# 0011 Connect a device with a dashboard code or QR — Status: Accepted
Date: 2026-11-08

## Context
Today two Admin screens set a device up: "Central dashboard" (pairing code, ADR 0010) and
"Remote access" (pasted Cloudflare API token, ADR 0009). With minicam-admin ADR 0012 and minicam
ADR 0010, one dashboard code sets up both. The owner wants to enter it as a code like `ABCD-EFGH`
or by scanning a QR.

## Decision
- **One screen**, Admin → **Connect to dashboard** (Android and iOS, admin + LAN only), replaces
  "Central dashboard". The form takes the dashboard address and code, with the same checks as ADR
  0010 (`https://` host, no path; 8 letters and digits, dashes and spaces ignored).
- **QR through a deep link.** The dashboard QR encodes
  `minicam://pair?url=<dashboard origin>&code=<code>`. The system camera opens the app.
  - Android: intent filter on the `minicam` scheme, host `pair`.
  - iOS: URL type `minicam`.
  - The app validates both parameters and fills the form. It never sends on its own: the admin
    sees the dashboard host in large type and taps **Connect**.
  - If the app is not on a LAN connection to a device as admin, it says so and keeps the values
    for 10 minutes in memory only.
  - No in-app scanner, camera permission or scanning dependency.
- **Progress:** after `POST /api/system/central` the screen shows two rows, "Dashboard" and
  "Remote access" (`installing` → `running` / `error` / `skipped`), polling
  `GET /api/system/tunnel`. On `running` it shows `https://<hostname>` as the address to sign in
  at from outside the home network (the app keeps one server address, so nothing is saved).
- **Remote access** (ADR 0009) stays for devices without a dashboard. It becomes read-only with
  "Managed by the dashboard" when the tunnel `source` is `central`.
- The code and URL live only in screen state and are cleared after a result. No token, central or
  tunnel, ever reaches the phone.

## Alternatives considered
- **In-app QR scanner** (CameraX + ML Kit / AVFoundation): a scan without leaving the app, but a
  camera permission and, on Android, a new dependency. Revisit if the deep link proves clumsy.
- **Universal / App Links on the dashboard domain:** no custom scheme, but they need files hosted
  per dashboard and break for self-hosted dashboards.
- **Auto-connect on opening the link:** one fewer tap, but a QR from anyone could repoint the
  device's WAN to another Cloudflare account (minicam ADR 0010 Consequences).

## Consequences
- The custom scheme can be claimed by another app on Android. The link carries only a one-time
  code that is useless without the admin's LAN session, so this is acceptable.
- Simpler setup: connect to Wi-Fi (ADR 0008), then one code. The Cloudflare API token path is
  needed only without a dashboard.
- Specs needed: the deep link handler, the merged screen and the progress rows, with tests for
  link parsing and validation on both platforms.
