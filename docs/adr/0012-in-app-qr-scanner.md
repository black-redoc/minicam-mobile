# 0012 In-app QR scanner for the dashboard code — Status: Accepted
Date: 2026-11-08

## Context
ADR 0011 opens "Connect to dashboard" from the dashboard's QR through the system camera
(`minicam://pair` deep link) and left an in-app scanner out to avoid a camera permission and a
scanning dependency. In use, the owner expects a "Scan QR code" button inside the app, next to
the code field, and a way to reach it from Remote access.

## Decision
- **Connect to dashboard** gets a **Scan QR code** button. A scanned value goes through the same
  `PairLink` parsing as the deep link and fills the form the same way: the dashboard host is shown
  for confirmation and only **Connect** sends. Anything else shows "That QR is not a minicam
  dashboard code."
- **Remote access** gets a short "Have a code from your dashboard?" entry that opens Connect to
  dashboard with the scanner, since the dashboard's code sets up remote access too. The Cloudflare
  API token path stays for devices without a dashboard.
- **Android:** CameraX (`camera-camera2`, `camera-lifecycle`, `camera-view`) for the preview
  and frames, ZXing `core` to decode QR codes on the phone (QR format only). The `CAMERA`
  permission is asked the first time the scanner opens; denied → a message pointing to Settings.
  `android.hardware.camera.any` is optional, so phones without a camera still install the app.
- **iOS:** AVFoundation (`AVCaptureMetadataOutput`, `.qr`) in a sheet, no dependency.
  `NSCameraUsageDescription` explains the use; a denied permission shows how to allow it in
  Settings. The session stops as soon as one value is read.
- Nothing scanned is stored; the decoded text lives only in the screen's state.

## Alternatives considered
- **Google code scanner (Android):** no `CAMERA` permission and no viewfinder of our own, but it
  pulls ML Kit and Google's `datatransport` telemetry (usage reports to Google) plus
  `ACCESS_NETWORK_STATE` / `WAKE_LOCK`, against the "no third-party analytics" invariant. Rejected.
- **CameraX + ML Kit (Android):** fast, but ML Kit brings the same telemetry.
- **VisionKit `DataScannerViewController` (iOS):** less code, but needs an A12 chip or newer and
  still the camera permission; AVFoundation covers every iOS 17 device.
- **Keep the system camera only (ADR 0011):** no new permission or dependency, but the owner
  found it unclear where to scan.

## Consequences
- Both apps ask for the camera the first time the scanner opens; nothing leaves the phone.
- Android gains CameraX and ZXing core (DEPENDENCIES.md); a unit test keeps ML Kit, Firebase,
  `datatransport` and Play services out of the lockfile.
- Supersedes the "In-app QR scanner" alternative of ADR 0011; the deep link stays.
