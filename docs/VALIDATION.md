# Validation

## Test strategy
- Unit tests for everything outside the UI: URL rules, API request building/parsing, Access
  handoff, stream reconnection policy, PTZ dispatch.
- Android: JUnit 4 + OkHttp MockWebServer (`android/app/src/test`).
- iOS: Swift Testing in `ios/MinicamKit/Tests` (runs on macOS with Command Line Tools, no Xcode).
- Manual device check per milestone: LAN and WAN (tunnel) login, stream, PTZ, logout.

## Commands
Android (from `android/`, needs JDK 17 + Android SDK, see DEVELOPMENT.md):
```bash
./gradlew testDebugUnitTest      # unit tests
./gradlew lintDebug              # Android lint
./gradlew assembleDebug          # build
```

iOS:
```bash
cd ios/MinicamKit && swift build && swift test           # core library (no Xcode needed)
cd ios/MinicamApp && xcodegen generate                   # Xcode project
xcodebuild -project MinicamApp.xcodeproj -scheme MinicamApp \
  -destination 'platform=iOS Simulator,name=iPhone 16' build
```

## Definition of Done
- [ ] Every acceptance criterion has a passing test
- [ ] All commands above pass for the touched platform(s)
- [ ] SECURITY.md checklist reviewed
- [ ] Spec tasks ticked; ROADMAP.md and PLAN.md "Current focus" updated
- [ ] PROGRESS.md entry; commit done; graphs refreshed
