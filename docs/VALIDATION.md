# Validation

## Test strategy
- Unit tests for everything outside the UI: URL rules, API request building/parsing, Access
  handoff, stream reconnection policy, PTZ dispatch.
- Android: JUnit 4 + OkHttp MockWebServer (`android/app/src/test`).
- iOS: Swift Testing in `ios/MinicamKit/Tests` (runs on macOS with Command Line Tools, no Xcode).
- Manual device check per milestone: LAN and WAN (tunnel) login, stream, PTZ, logout.

## Commands
Android (from `android/`, with `JAVA_HOME` set as in DEVELOPMENT.md):
```bash
./gradlew testDebugUnitTest      # unit tests
./gradlew lintDebug              # Android lint (0 warnings expected)
./gradlew assembleDebug assembleRelease   # build (release runs R8)
./gradlew :app:dependencies --write-locks # after changing dependencies
```

iOS:
```bash
cd ios/MinicamKit && swift build && swift test           # core library (no Xcode needed)
cd ios/MinicamApp && xcodegen generate                   # Xcode project
xcodebuild -project MinicamApp.xcodeproj -scheme MinicamApp \
  -destination 'generic/platform=iOS Simulator' build    # no Swift warnings expected
```

## Definition of Done
- [ ] Every acceptance criterion has a passing test
- [ ] All commands above pass for the touched platform(s)
- [ ] SECURITY.md checklist reviewed
- [ ] Spec tasks ticked; ROADMAP.md and PLAN.md "Current focus" updated
- [ ] PROGRESS.md entry; commit done; graphs refreshed
