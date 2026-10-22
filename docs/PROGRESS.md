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
