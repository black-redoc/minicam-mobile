# Development

## Prerequisites
- **Android:** JDK 17 and Android Studio (or the command-line SDK) with platform 35.
  `brew install --cask temurin@17 android-studio`
- **iOS:** full Xcode (not only Command Line Tools), then
  `sudo xcode-select -s /Applications/Xcode.app` and `brew install xcodegen`.
- A running minicam instance (see the minicam README) with at least one user who has
  `can_view` on a camera.

## First-time setup
```bash
# Android: the wrapper jar is not committed until it is generated with a trusted Gradle
cd android && gradle wrapper --gradle-version 8.11.1 --distribution-type bin
# then commit gradle/wrapper/gradle-wrapper.jar, gradlew, gradlew.bat

# iOS
cd ios/MinicamApp && xcodegen generate && open MinicamApp.xcodeproj
```

## Run
- Android: `./gradlew installDebug` with a device/emulator connected.
  From the emulator, the host machine is `http://10.0.2.2:8000`.
- iOS: run the `MinicamApp` scheme from Xcode. The simulator reaches the host at
  `http://localhost:8000`.

## Conventions
- Package / bundle id: `dev.minicam.mobile`. Callback scheme: `minicam://access-callback`.
- Keep Android and iOS behavior identical; when one changes, update the other or open an issue.
- Theme values come only from ARCHITECTURE.md "Theme" (mirrors minicam `main.css`).
- Code, comments and docs in English. Commits follow Conventional Commits.

## Workflow
Plan-driven: PLAN.md → active spec → implement in small steps → tests → PROGRESS.md entry.
