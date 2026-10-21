# 0001 Native apps: Kotlin + Jetpack Compose and SwiftUI — Status: Accepted
Date: 2026-10-21

## Context
minicam needs mobile clients for viewing cameras and PTZ. The user asked for one directory per
platform and a look based on the desktop dashboard (dark slate + sky accent, `main.css`).
The client logic is small (REST + one binary WebSocket), so code sharing buys little.

## Decision
Two native apps in one repo: `android/` (Kotlin, Jetpack Compose, OkHttp) and `ios/` (SwiftUI,
URLSession). iOS core logic lives in a Foundation-only Swift package (`MinicamKit`) so it can be
tested with `swift test` without Xcode. The Xcode project is generated with XcodeGen.

## Alternatives considered
- Kotlin Multiplatform: shared networking, but more build complexity for little shared code.
- React Native / Flutter: one codebase, but a JS/Dart runtime and a large dependency tree for a
  security-sensitive app; doesn't match the "one directory per platform" request.
- PWA from the existing dashboard: cheapest, but no Keychain/Keystore storage and Access login
  inside a PWA is awkward on iOS.

## Consequences
- Every feature is implemented twice; ARCHITECTURE.md is the shared contract.
- Smallest possible dependency surface; platform-native video and secure storage.
