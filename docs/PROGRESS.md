# Progress

## 2026-10-21 — Bootstrap (M0) + spec 001 skeleton
- Done: repo, docs, ADR 0001 (native stack) and 0002 (Access handoff, Proposed); iOS MinicamKit +
  SwiftUI app; Android Compose app; spec 002 contract for the backend endpoints
- Commit: 934d8c6 (docs), dd8d8f9 (iOS), 05164e5 (Android)
- Tests: iOS MinicamKit `swift test` 16/16 passing. Android tests written, not run (no JDK/SDK,
  KI-1). iOS app target not compiled (no Xcode, KI-2)
- Next: install toolchains, run Android tests/lint/build and the Xcode build; implement spec 002
  in minicam

## 2026-10-21 — Spec 002 (backend) + dependency bump + first real builds
- Done: spec 002 implemented in minicam (branch `feat/mobile-endpoints`, commit 150ede1, 751 tests
  pass, coverage 99.7%); Android deps bumped to latest stable (AGP 9.4.1, Kotlin 2.4.21, OkHttp 5.5.0,
  Gradle 9.8.2 wrapper, lockfiles); iOS app generated with XcodeGen and built
- Commit: 03e0616 (Android bump), this entry's commit (iOS docs)
- Tests: Android 17/17, lint 0 warnings, debug + release build OK; iOS MinicamKit 16/16, app
  builds for the simulator with no warnings
- Next: merge the minicam branch, end-to-end check on devices, CI

## 2026-10-21 — End-to-end check on emulator/simulator + README screenshots
- Done: both apps signed in to a local minicam (spec 002 branch) and listed cameras; found and
  fixed KI-6 (Android read responses on the main thread); fixed truncated iOS toolbar title and
  list spacing on Android; iOS `ScreenshotTests` UI test; screenshots in the README; minicam PR #12
- Commit: d91de5b, 05104e2, db44764, f606340
- Tests: Android 18/18, lint 0 warnings; iOS MinicamKit 16/16, UI test passing
- Next: merge minicam PR #12, create the GitHub remote for this repo, live video check with a real
  camera, CI

## 2026-10-21 — Spec 003 CI/CD
- Done: `ci.yml` (tests, lint, installable builds on PRs), `release.yml` (GitHub release on merge
  to `master`), env-based Android release signing, ADR 0003
- Commit: 81210da (PR #1)
- Tests: PR run 39716417543 green (Android 4m59s, iOS 1m18s), artifacts `android` + `ios`
  uploaded; local apksigner/aapt2 check of signing and versionCode
- Next: merge PR #1, check the first release, set the Android keystore secrets

## 2026-10-22 — KI-8 PTZ buttons never move the camera
- Done: PTZ dispatcher no longer cancels pending requests; one in flight, latest action queued
- Commit: 28e72c5
- Tests: Android 21/21 + lint + debug/release builds; iOS `swift test` 19/19 + simulator build
- Next: check PTZ on a real camera over LAN and WAN

## 2026-10-22 — Spec 004 fullscreen camera view
- Done: fullscreen toggle on the video, floating glass exit/PTZ buttons, glass PTZ panel on the
  right with an (x) close button, landscape + hidden system bars, on Android and iOS
- Commit: 7a0f38c
- Tests: Android 21/21 + lint 0 warnings + debug/release builds; iOS `swift test` 19/19,
  simulator build + UI test build with no Swift warnings
- Next: visual check on emulator/simulator (`ScreenshotTests` now captures fullscreen) and device

## 2026-10-22 — Spec 005 admin screens (ADR 0004)
- Done: Camera settings (CRUD, ONVIF search, detection classes, notifications mode), AI detection
  (catalog install, engine settings, fetch model from URL) and Admin (users, permissions, WAN
  access, global notifications) on Android and iOS; minicam error `detail`s shown as-is
- Commit: 298d640 (docs), 057b3ea (Android), 72ea795 (iOS)
- Tests: Android 38/38 + lint 0 warnings + debug/release builds; iOS `swift test` 36/36,
  simulator build + UI test build with no Swift warnings
- Next: visual check against a local minicam with an admin account (emulator/simulator)

## 2026-10-22 — Spec 006 event recordings (ADR 0005)
- Done: Recordings (clips by day, camera filter, paging, player, download, admin delete) and the
  admin "Event recordings" card on Android (Media3 ExoPlayer) and iOS (AVPlayer)
- Commit: e0fe58b (docs), 0dd821a (Android), 8b547f6 (iOS)
- Tests: Android 48/48 + lint 0 warnings + debug/release builds; iOS `swift test` 46/46,
  simulator build + UI test build with no Swift warnings
- Next: manual check against a local minicam with recordings (emulator/simulator), WAN playback

## 2026-10-22 — Spec 006 AC9 fullscreen recordings player
- Done: fullscreen player (landscape, hidden system bars, no restart); Android uses the Media3
  controls' fullscreen button, iOS a glass button like the live view
- Commit: 7b0770b (Android), bcf121f (iOS)
- Tests: Android 48/48 + lint 0 warnings + debug/release builds; iOS `swift test` 46/46,
  simulator + UI test builds with no Swift warnings
- Next: manual check on emulator/simulator against a local minicam with recordings

## 2026-10-22 — Spec 006 AC10-AC11 time bar colors, iOS controls, player animation
- Done: sky played part on the time bar (both); iOS custom controls over AVPlayerLayer; player
  fades in while the list slides down, one 0.5 s ease-in-out curve (both)
- Commit: 3ed1002, 9dc4954 (Android), 35e6503 (iOS)
- Tests: Android 48/48 + lint 0 warnings + debug/release builds; iOS `swift test` 47/47,
  simulator + UI test builds with no Swift warnings
- Next: visual check of controls and animation on emulator/simulator

## 2026-10-23 — Spec 007 skeleton loaders
- Done: shimmering placeholders shaped like the content on first load (camera list, recordings,
  admin screens, live view before the first frame) on Android and iOS; action spinners kept
- Commit: db91b3d (docs), 3477461 (Android), 066929c (iOS)
- Tests: Android unit tests + lint 0 warnings + debug/release builds; iOS `swift test` 47/47,
  simulator + UI test builds with no Swift warnings
- Next: visual check on emulator/simulator against a local minicam (slow network helps)

## 2026-10-23 — Scrollable detection classes
- Done: camera add/edit form shows at most 6 detection classes; the rest scroll inside the list
- Commit: be76066 (Android), a1d415e (iOS)
- Tests: Android `compileDebugKotlin`; iOS simulator build with no Swift warnings
- Next: visual check with a detection backend exposing more than 6 classes

## 2026-10-23 — Spec 008 digital zoom and clip navigation
- Done: zoom in/out glass buttons (1×-4×, drag to pan) on the live view and the recordings
  player; player "next" plays the clip below (loads the next page), "back" restarts the clip
- Commit: bc61e36 (docs), edd7ffe (Android), 61c12b1 (iOS)
- Tests: Android 51/51 + lint 0 warnings + debug/release builds; iOS `swift test` 50/50,
  simulator build with no Swift warnings
- Next: visual check on emulator/simulator (zoom, pan, next/back) against a local minicam

## 2026-10-23 — Spec 009 live fMP4 video and audio
- Done: live view plays the camera's own H.264/HEVC as fMP4 (MJPEG fallback), detection boxes
  drawn by the app, speaker button gated by `can_listen`, Listen column in Admin (KI-9 fixed)
- Commit: d1f00a5 (docs), a81420f (Android), 66a734e (iOS)
- Tests: Android 63/63 + lint 0 warnings + debug/release builds; iOS `swift test` 61/61,
  simulator + UI test builds with no Swift warnings
- Next: manual check against a minicam with an HEVC camera on LAN and WAN, audio on/off

## 2026-10-24 — Spec 010 sub-stream only
- Done: removed the camera config `stream` field and the Main/Sub picker (minicam now always
  reads the sub-stream); older servers that still send the key keep working
- Commit: b6322ca (docs), 4af1bdb (Android), 88bef3d (iOS)
- Tests: Android 64/64 + lint 0 issues + debug build; iOS `swift test` 62/62, simulator build OK
- Next: manual check of the camera form; spec 009 manual checks still open

## 2026-10-24 — Spec 011 live view time limit
- Done: each camera open streams for at most 30 s (paused while in background), then the
  connection closes and "Viewing ended" shows; reopen the camera to watch again
- Commit: 814b974 (docs), 3ba92af (Android), a186ee1 (iOS)
- Tests: Android 67/67 + lint 0 issues + debug build; iOS `swift test` 65/65, simulator build OK
- Next: manual check of AC4/AC5 on emulator/simulator (MJPEG and fMP4, audio on)

## 2026-10-24 — Spec 011: return to the list
- Done: when the 30 s are up the app goes back to the camera list on its own (replaces the
  "Viewing ended" notice and the "Ended" badge)
- Commit: see `git log feat/stream-time-limit`
- Tests: Android 67/67 + lint 0 issues + debug build; iOS simulator build OK
- Next: manual check of AC4/AC5 on emulator/simulator

## 2026-10-24 — Spec 011: countdown
- Done: "Closes in N s" pill over the live view (regular and fullscreen), red for the last 10 s
- Commit: see `git log feat/stream-time-limit`
- Tests: Android 68/68 + lint 0 issues + debug build; iOS `swift test` 66/66, simulator build OK
- Next: manual check of AC4/AC5/AC7 on emulator/simulator

## 2026-11-08 — M2.12 Connect to dashboard with a code or QR (ADR 0011)
- Done: `PairLink` (deep link parsing), `PendingPairLink` (10 min, memory), `ConnectProgress` and
  `connectToDashboard` on both platforms; `minicam://pair` intent filter / `onOpenURL`; "Central
  dashboard" became "Connect to dashboard" with host confirmation and progress rows; Remote access
  read-only when the dashboard manages the tunnel.
- Commit: see git log (feat: connect to the dashboard with a code or its QR), branch feat/connect-with-code
- Tests: Android unit tests pass (8 new), lint only the 5 existing version notices, debug + release
  build; iOS `swift test` 91/91, simulator build OK.
- Next: scan the QR on real phones (Android, iPhone) against the deployed dashboard.

## 2026-11-08 — M2.13 In-app QR scanner (ADR 0012)
- Done: "Scan QR code" in Connect to dashboard and "Scan the dashboard's QR" in Remote access;
  Android CameraX + ZXing (`QrDecoder`, `QrScannerDialog`), iOS AVFoundation (`QRScannerSheet`);
  `PairLink.fromScan`. Google code scanner rejected: it pulls ML Kit telemetry.
- Commit: see git log (feat: scan the dashboard's QR inside the app), branch feat/in-app-qr-scanner
- Tests: Android unit tests pass (3 new), lint only the 5 existing version notices, debug + release
  build; iOS `swift test` 92/92, simulator build OK with no warnings.
- Next: scan on real phones.

## 2026-11-09 — KI-10 AI detection timeout (Android)
- Done: catalog, install and model fetch calls use a 120 s read timeout; other calls keep 10 s
- Commit: see git log (fix: give the AI detection catalog calls ...), branch fix/slow-admin-timeouts
- Tests: Android 98/98, lint only the 5 existing version notices; cause seen on the S23 (SocketTimeoutException in logcat)
- Next: owner confirms AI detection loads over WAN

## 2026-11-09 — ADR 0013 Local / Remote switch
- Done: both addresses saved per server (old `server_url` migrated), Local / Remote switch on the
  camera list and login, add-address dialog with LAN discovery, sign-out never waits on the server
- Commit: see git log (feat: switch between the local and remote address ...), branch feat/lan-wan-switch
- Tests: Android 103/103, lint only the 5 existing version notices, installed on the S23 (migration kept the session); iOS
  `swift test` 97/97, simulator build with no warnings (simulator not runnable here: CoreSimulator
  out of date)
- Next: manual Remote ↔ Local check on the S23 and an iPhone

## 2026-11-09 — Spec 012 Android push client (ADR 0007)
- Done: `push/` package (PushPayload AES-GCM decrypt, PushRegistration opt-in/out, FirebasePush
  hand-made init, MinicamMessagingService, notification that opens the camera), menu switch with
  `POST_NOTIFICATIONS`, firebase-messaging 26.0.1; telemetry guard amended (ADR 0007 exception)
- Commit: see git log (feat: detection notifications on Android ...), branch feat/android-push
- Tests: Android 108/108, lint only the 5 existing version notices, debug + release build
- Next: AC9/AC10 on the S23 once minicam #44 and minicam-admin #5 are deployed with the FCM secret
