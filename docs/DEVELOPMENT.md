# Development

## Prerequisites
- **Android:** Android Studio with SDK platform 37. Gradle runs on Android Studio's bundled JDK:
  `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"`.
  `android/local.properties` (gitignored) needs `sdk.dir=$HOME/Library/Android/sdk`.
- **iOS:** full Xcode and `brew install xcodegen`. Either run
  `sudo xcode-select -s /Applications/Xcode.app` once, or prefix commands with
  `DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer`.
- A running minicam instance (see the minicam README) with at least one user who has
  `can_view` on a camera.

## First-time setup
```bash
# Android: the Gradle wrapper is committed; the first ./gradlew run downloads Gradle and
# checks it against distributionSha256Sum.

# iOS
cd ios/MinicamApp && xcodegen generate && open MinicamApp.xcodeproj
```

## Run
- Android: `./gradlew installDebug` with a device/emulator connected.
  From the emulator, the host machine is `http://10.0.2.2:8000`.
- iOS: run the `MinicamApp` scheme from Xcode. The simulator reaches the host at
  `http://localhost:8000`.

## Release signing (Android)
Release builds use the key in `MINICAM_KEYSTORE_PATH` (+ `MINICAM_KEYSTORE_PASSWORD`,
`MINICAM_KEY_ALIAS`, `MINICAM_KEY_PASSWORD`); without it they fall back to the debug key. For CI,
create the key once, back it up outside the repo, and set the repo secrets:
```bash
keytool -genkeypair -keystore minicam-release.jks -alias minicam -keyalg RSA -keysize 4096 -validity 10000
gh secret set ANDROID_KEYSTORE_BASE64 < <(base64 -i minicam-release.jks)
gh secret set ANDROID_KEYSTORE_PASSWORD; gh secret set ANDROID_KEY_ALIAS; gh secret set ANDROID_KEY_PASSWORD
```
Losing the key means users must uninstall to update. iOS builds from CI are unsigned (ADR 0003).

## Conventions
- Package / bundle id: `dev.minicam.mobile`. Callback scheme: `minicam://access-callback`.
- Keep Android and iOS behavior identical; when one changes, update the other or open an issue.
- Theme values come only from ARCHITECTURE.md "Theme" (mirrors minicam `main.css`).
- Code, comments and docs in English. Commits follow Conventional Commits.

## Workflow
Plan-driven: PLAN.md → active spec → implement in small steps → tests → PROGRESS.md entry.
