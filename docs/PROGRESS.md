# Progress

## 2026-10-21 — Bootstrap (M0) + spec 001 skeleton
- Done: repo, docs, ADR 0001 (native stack) and 0002 (Access handoff, Proposed); iOS MinicamKit +
  SwiftUI app; Android Compose app; spec 002 contract for the backend endpoints
- Commit: 934d8c6 (docs), dd8d8f9 (iOS), 05164e5 (Android)
- Tests: iOS MinicamKit `swift test` 16/16 passing. Android tests written, not run (no JDK/SDK,
  KI-1). iOS app target not compiled (no Xcode, KI-2)
- Next: install toolchains, run Android tests/lint/build and the Xcode build; implement spec 002
  in minicam
