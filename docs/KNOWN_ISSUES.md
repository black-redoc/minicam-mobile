# Known issues

## KI-1 Android app not built or tested yet — Status: Open
No JDK / Android SDK on the bootstrap machine. Unit tests are written but have never run.
Repro: `cd android && ./gradlew testDebugUnitTest` (fails: no wrapper/JDK). Fix: DEVELOPMENT.md setup.

## KI-2 iOS app target not built yet — Status: Open
Only Command Line Tools installed; `MinicamKit` builds and its tests pass, the SwiftUI target has
never compiled. Fix: install Xcode + XcodeGen, then VALIDATION.md iOS commands.

## KI-3 Backend endpoints missing — Status: Open (blocker for M1)
`GET /api/me/cameras` and `GET /api/mobile/access-handoff` don't exist in minicam yet
(spec 002). Until then the camera list is empty for every user and WAN login can't complete.

## KI-4 LAN traffic is clear text — Status: Open (inherited from minicam)
minicam serves LAN over plain HTTP, so password and session cookie are visible to anyone on the
same Wi-Fi. Mitigation: use the WAN (https) hostname on untrusted networks.
