# Validation

## Test strategy
- Unit tests for everything outside the UI: URL rules, API request building/parsing, Access
  handoff, stream reconnection policy, PTZ dispatch.
- Android: JUnit 4 + OkHttp MockWebServer (`android/app/src/test`).
- iOS: Swift Testing in `ios/MinicamKit/Tests`, run on macOS with `swift test`.
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
cd ios/MinicamKit && swift build && swift test           # core library (use Xcode's toolchain, KI-5)
cd ios/MinicamApp && xcodegen generate                   # Xcode project
xcodebuild -project MinicamApp.xcodeproj -scheme MinicamApp \
  -destination 'generic/platform=iOS Simulator' build    # no Swift warnings expected
```

## CI/CD
`.github/workflows/ci.yml` runs the commands above on every PR (ADR 0003) and uploads the
installable builds as artifacts; `release.yml` publishes them as a GitHub release on every merge
to `master`. UI tests are not run in CI (they need a live minicam).

## README screenshots
Run a minicam with a user who can view a camera named `entrada`, then:
```bash
# iOS: ScreenshotTests logs in and attaches ios-login / ios-cameras / ios-camera / ios-fullscreen*,
# plus ios-camera-settings / ios-ai-detection / ios-admin when the account is an admin
TEST_RUNNER_MINICAM_SERVER=http://localhost:8000 TEST_RUNNER_MINICAM_EMAIL=… \
TEST_RUNNER_MINICAM_PASSWORD=… xcodebuild test -project MinicamApp.xcodeproj \
  -scheme MinicamApp -destination 'platform=iOS Simulator,name=iPhone 18 Pro' -resultBundlePath shots.xcresult
xcrun xcresulttool export attachments --path shots.xcresult --output-path shots/
```
Android: `adb reverse tcp:8000 tcp:8000`, sign in to `http://127.0.0.1:8000` in the emulator and
`adb exec-out screencap -p > shot.png`. Without the `TEST_RUNNER_MINICAM_*` variables the UI
test is skipped. Run the emulator and simulator one at a time; together they starve each other.

## Definition of Done
- [ ] Every acceptance criterion has a passing test
- [ ] All commands above pass for the touched platform(s)
- [ ] SECURITY.md checklist reviewed
- [ ] Spec tasks ticked; ROADMAP.md and PLAN.md "Current focus" updated
- [ ] PROGRESS.md entry; commit done; graphs refreshed
