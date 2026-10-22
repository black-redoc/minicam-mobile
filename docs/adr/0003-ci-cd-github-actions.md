# 0003 CI/CD on GitHub Actions with GitHub Releases — Status: Accepted
Date: 2026-10-21

## Context
Both apps were only built and tested by hand. We want every PR checked and every merge to `master`
to produce apps a user can install, without store accounts (store distribution is in the Backlog).

## Decision
- `.github/workflows/ci.yml` runs on every PR: Android tests, lint, debug + release APKs
  (`ubuntu-24.04`, JDK 25); iOS `swift test`, XcodeGen, Release builds for device and simulator
  (`macos-26`, Xcode 26.6). Installable files are uploaded as workflow artifacts.
- `.github/workflows/release.yml` runs on push to `master`: reuses `ci.yml`, then publishes a
  GitHub release `v<versionName>-build.<run number>` with the files and `SHA256SUMS.txt`.
- Android release signing reads `MINICAM_KEYSTORE_*` env vars (CI secrets `ANDROID_KEYSTORE_*`).
  Without them the release APK is signed with the debug key so it is still installable.
- iOS is built with `CODE_SIGNING_ALLOWED=NO`: an unsigned `.ipa` (re-signed by AltStore,
  Sideloadly or Xcode) and a simulator `.app`. Signed IPAs/TestFlight need an Apple Developer
  account and are out of scope.
- `versionCode` / `CURRENT_PROJECT_VERSION` = workflow run number, so builds upgrade in place.
- Actions are pinned by commit SHA; XcodeGen is downloaded at a pinned version and its SHA-256 is
  checked (docs/DEPENDENCIES.md).

## Alternatives considered
- Fastlane: extra Ruby toolchain and plugins for what a few shell steps do.
- Firebase App Distribution / TestFlight: needs third-party or paid accounts.
- Tag-triggered releases: the user asked for delivery on every merge to `master`.

## Consequences
- Every merge creates a release; old ones can be pruned by hand.
- Until the keystore secrets are set, each release APK has a different signing key and must be
  uninstalled before installing a newer build.
- The CI Xcode (26.6) is older than the local one (27.1); the app must keep building on both.
