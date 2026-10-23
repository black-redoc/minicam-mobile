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
