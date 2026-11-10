# 0014 Remote access only through the central dashboard — Status: Accepted
Date: 2026-11-10

## Context
ADR 0009 added Admin → "Remote access": the admin pastes a Cloudflare API token and the app
creates the tunnel, DNS record and Access application, then hands the tunnel token to minicam.
ADR 0011 kept it for devices without a dashboard. Since then every device is paired with the
central dashboard (minicam-admin), which provisions the tunnel itself (minicam ADR 0010), so
the in-app setup is a second, rarely used path that holds an account-wide token on the phone.

## Decision
- Remove "Remote access" from the admin menu on Android and iOS, with its screen and the
  Cloudflare API client (`CloudflareSetup` on both platforms).
- The app no longer calls `POST` / `DELETE /api/system/tunnel` and never talks to
  `api.cloudflare.com`. It still reads `GET /api/system/tunnel` to show the tunnel coming up in
  "Connect to dashboard" (ADR 0011).
- The "Scan QR code" shortcut from Remote access (ADR 0012) goes with it; the scanner stays in
  "Connect to dashboard".
- Supersedes ADR 0009; amends ADR 0011's "Remote access stays for devices without a dashboard".

## Alternatives considered
- **Keep it for devices without a dashboard:** no current device needs it, and it keeps an
  account-wide credential flow and `api.cloudflare.com` in the app.
- **Hide the menu entry but keep the code:** dead code that still has to be maintained and
  reviewed.

## Consequences
- A device without a dashboard gets remote access only by the manual steps in minicam's
  `app/docs/cloudflare.md` (SSH and the Cloudflare dashboard).
- The ARCHITECTURE.md invariant shrinks back to the configured host and its Access team domain
  (plus the opt-in push exception, ADR 0007). No Cloudflare API token is ever typed in the app.
- minicam's tunnel setup routes (minicam ADR 0008) stay; dropping them is the server's call.
