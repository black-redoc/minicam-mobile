# 003 CI/CD and installable builds — Status: In progress
## Goal
Every PR runs tests, lint and builds for both apps; every merge to `master` publishes installable
Android and iOS builds as a GitHub release ([ADR 0003](../adr/0003-ci-cd-github-actions.md)).

## Acceptance criteria
- [ ] AC1: A PR runs Android unit tests + lint and iOS `swift test`; any failure fails the check →
  test: `CI / Android`, `CI / iOS` jobs on the PR
- [ ] AC2: The PR run uploads `minicam-android.apk`, `minicam-android-debug.apk`,
  `minicam-ios-unsigned.ipa`, `minicam-ios-simulator.zip` → test: artifacts `android`, `ios`
- [x] AC3: With `MINICAM_KEYSTORE_*` set the release APK is signed with that key and carries
  `-PversionCode`; without them it is signed with the debug key → test: local `apksigner verify`
  + `aapt2 dump badging` (2026-10-21)
- [ ] AC4: A push to `master` creates release `v0.1.0-build.<n>` with the four files and
  `SHA256SUMS.txt` → test: `Release` workflow after merging this spec's PR

## Design
See ADR 0003. Secrets: `ANDROID_KEYSTORE_BASE64`, `ANDROID_KEYSTORE_PASSWORD`,
`ANDROID_KEY_ALIAS`, `ANDROID_KEY_PASSWORD` (docs/DEVELOPMENT.md "Release signing").

## Security notes
Workflows default to `contents: read`; only the publish job gets `contents: write`. The keystore
is decoded to `$RUNNER_TEMP` and deleted after the build. Fork PRs get no secrets. Actions pinned
by SHA; XcodeGen checksum verified.

## Tasks
- [x] Gradle: env-based release signing + `versionCode` property
- [x] `ci.yml` and `release.yml`
- [ ] Green PR run; release after merge
- [ ] Set the keystore secrets (repo owner)

## Out of scope
Signed iOS builds, TestFlight, Play Store, UI tests in CI (need a running minicam).
