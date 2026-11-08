# 0010 Connect the device to the central dashboard from the app — Status: Accepted
Date: 2026-11-08

## Context
Central mode (minicam-admin) needed the dashboard's device token copied into the Pi's `.env` over
SSH. The owner wants every device setup step in the app. minicam ADR 0009 and minicam-admin ADR
0011 add a one-time pairing code that the Pi exchanges for its token itself.

## Decision
- Admin menu → **Central dashboard** (Android and iOS), LAN only like Remote access: shows whether
  the device is connected, its dashboard and last sync; a form with the dashboard address and the
  code from "Pair device" (`POST /api/system/central`); "Pair again" (rotates the token) and
  "Disconnect" (`DELETE`). A device configured in `.env` is shown read-only.
- Input is checked in the app before sending (`https://` host with no path; 8 letters and digits,
  dashes and spaces ignored); minicam and the dashboard check it again.
- The code lives only in the screen's state and is cleared after pairing; the device token never
  reaches the phone.

## Alternatives considered
- Pasting the token or scanning a QR (minicam-admin ADR 0011): the token would be on the phone.

## Consequences
- The app does not talk to the dashboard itself; only minicam does.
- After connecting, the Admin screen's WAN checkboxes are locked (the dashboard decides).
