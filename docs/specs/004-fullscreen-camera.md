# 004 Fullscreen camera view — Status: In progress
## Goal
The camera screen can switch to a fullscreen, landscape view of the live stream with floating
glass controls, so the feed uses the whole display while PTZ stays one tap away.

## Acceptance criteria
UI-only feature: checked manually on both platforms (emulator/simulator against a local minicam).
The iOS `ScreenshotTests` UI test walks AC1-AC3/AC5 and attaches `ios-fullscreen` and
`ios-fullscreen-ptz`.
- [ ] AC1: A floating button on the video enters fullscreen: black background, video fitted to
  the whole screen, landscape, system bars and navigation bar hidden → manual
- [ ] AC2: A floating button exits fullscreen and restores the regular screen and orientation;
  Android back also exits fullscreen before leaving the camera → manual
- [ ] AC3: For cameras with PTZ, a floating "PTZ" button shows the PTZ pad on the right side of
  the screen; cameras without PTZ show no button → manual
- [ ] AC4: Floating buttons and the PTZ pad use a translucent glass look (iOS 26+ Liquid Glass,
  material blur on older iOS, translucent tint + light border on Android) → manual
- [ ] AC5: The PTZ panel has an (x) button that hides it; hold-to-move keeps the AC7b dispatch
  rules from spec 001 → manual + existing PTZ dispatcher tests
- [ ] AC6: The stream keeps running while entering/leaving fullscreen and rotating → manual

## Design
Same screen, two layouts (no new route): the stream state stays in `CameraScreen` /
`CameraView`, so toggling fullscreen does not reconnect. Android declares orientation/screen-size
`configChanges` so rotating does not recreate the activity. iOS locks orientations through an
app delegate (`OrientationLock`): any orientation but upside down normally, landscape only while
fullscreen; leaving fullscreen rotates back to portrait.
`PtzPad`/`PTZPad` gain a glass style flag. No new dependencies.

## Security notes
No new network calls, data or permissions.

## Tasks
- [x] Android: fullscreen layout, system bars, orientation, glass buttons, glass PTZ panel
- [x] iOS: fullscreen layout, orientation lock, glass buttons, glass PTZ panel
- [x] Builds, lint and tests pass on both platforms
- [ ] Visual check on emulator/simulator and on a real device

## Out of scope
Pinch-to-zoom, auto-hiding controls, picture-in-picture.
