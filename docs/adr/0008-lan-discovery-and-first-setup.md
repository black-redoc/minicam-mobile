# 0008 LAN discovery and first setup from the apps — Status: Accepted
Date: 2026-10-28

## Context
minicam now ships a ready-to-flash Raspberry Pi OS image (minicam ADR 0005). A new device boots
with no users and advertises `_minicam._tcp` over mDNS; its first admin can only be created from
the LAN, within 30 min of start, with the 4-digit code blinked by the Pi's green ACT LED
(minicam ADR 0006). Today the user types the server URL by hand and the apps have no first-run
setup (ROADMAP M2 non-goal).

## Decision
- **Discovery** on the login screen ("Find minicam on my network"): Android `NsdManager`, iOS
  `NWBrowser` for `_minicam._tcp`. Results are resolved to the private IPv4 (`http://<ip>:<port>`;
  Android does not resolve `.local` names for apps) and go through `ServerConfig`, so the
  existing LAN-only `http` rule still applies.
  No new dependency; no traffic leaves the LAN.
- iOS declares `NSLocalNetworkUsageDescription` and `NSBonjourServices = ["_minicam._tcp"]`; no
  multicast entitlement is needed. Android needs no new runtime permission (`NsdManager` uses the
  existing `INTERNET` permission).
- **The login screen is the setup screen:** for a LAN address (typed or picked) the app calls
  `GET /api/auth/setup`. With no user yet it switches to "Create admin account" (with a link back
  to "Sign in"), or says to power-cycle the device when the 30-minute window is over.
- **First setup:** (1) "Blink a code on the device" (`POST /api/auth/setup/claim`) and how to
  count the blinks; (2) code + admin email + password (`POST /api/auth/setup`); (3) on success the
  app keeps the session and opens the camera list; cameras are added from the existing admin
  screens (ONVIF discovery already exists).
- **Later:** "Connect to Wi-Fi" after setup (minicam ADR 0007) comes with minicam's Wi-Fi API.
  Cloudflare Tunnel is set up from Admin → Remote access (ADR 0009).

## Alternatives considered
- **Custom UDP broadcast:** needs Apple's multicast entitlement on iOS.
- **Subnet HTTP sweep:** slow, battery-heavy, only works on a /24.
- **Keep typing the URL:** works, but a first-time user doesn't know the Pi's IP.

## Consequences
- iOS shows the local network permission prompt the first time the user searches.
- The setup code, admin password and Wi-Fi password travel over LAN `http` (same limitation as
  login, SECURITY.md).
- The apps get a first-run flow; this lifts ROADMAP M2's "first-run setup" non-goal.
