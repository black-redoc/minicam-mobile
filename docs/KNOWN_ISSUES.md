# Known issues

## KI-1 Android app not built or tested yet — Status: Resolved 2026-10-21
Toolchain installed; unit tests, lint, debug and release builds pass. The first real build caught
an XML comment containing `--` in themes.xml (invalid XML), fixed in the same commit.

## KI-2 iOS app target not built yet — Status: Resolved 2026-10-21
Built with Xcode 27.1 + XcodeGen 2.46.0 for the simulator with no Swift warnings; launched on an
iPhone 18 Pro simulator and the login screen renders with the desktop theme.

## KI-3 Backend endpoints missing — Status: In review (blocker for M1)
`GET /api/me/cameras` and `GET /api/mobile/access-handoff` (spec 002) are implemented on minicam
branch `feat/mobile-endpoints` (commit 150ede1), not merged yet. Until a server runs that code the
camera list is empty for every user and WAN login can't complete.

## KI-4 LAN traffic is clear text — Status: Open (inherited from minicam)
minicam serves LAN over plain HTTP, so password and session cookie are visible to anyone on the
same Wi-Fi. Mitigation: use the WAN (https) hostname on untrusted networks.
