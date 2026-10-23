# Roadmap

## M0 - Bootstrap
- [x] Repo layout (`android/`, `ios/`, `docs/`), docs, ADRs 0001-0002
- [x] Android app skeleton (Kotlin + Jetpack Compose) with desktop design tokens
- [x] iOS app skeleton (SwiftUI) + `MinicamKit` Swift package with tests
- [x] Toolchains installed, Gradle wrapper generated (DEVELOPMENT.md)
- [x] Xcode project generated and the iOS app built
- [ ] CI for both apps + release on merge to `master` (specs/003-ci-cd.md, ADR 0003)

## M1 - Viewer v1 (specs/001-viewer-v1.md)
Scope: server URL, minicam login/logout, list of viewable cameras, live stream, hold-to-move PTZ,
Cloudflare Access login for WAN hosts.
- [ ] Backend prerequisites merged in minicam (specs/002; implemented on minicam branch `feat/mobile-endpoints`)
- [ ] Android: all spec 001 acceptance criteria passing
- [ ] iOS: all spec 001 acceptance criteria passing
- [ ] Tested on a real device on LAN and through the tunnel
- [ ] Fullscreen camera view with floating glass controls and PTZ panel (specs/004-fullscreen-camera.md)

Non-goals for M1: admin screens (moved to M2, ADR 0004), push notifications, recordings/playback (now M2.5, ADR 0005), multi-server profiles, tablets-specific layouts.

## M2 - Admin (specs/005-admin-screens.md, ADR 0004)
Scope: admin-only Camera settings (add/edit/delete, ONVIF discovery, detection classes,
notifications mode), AI detection (model catalog, engine settings, fetch model from URL) and
Admin (users, per-camera permissions, WAN access, global detection notifications).
- [ ] Android: all spec 005 acceptance criteria passing
- [ ] iOS: all spec 005 acceptance criteria passing
- [ ] Checked against a local minicam on emulator/simulator

Non-goals for M2: uploading `.onnx` files from the phone, first-run setup.

## M2.5 - Recordings (specs/006-recordings.md, ADR 0005)
Scope: event clip list (by day, camera filter, paging), player, download, admin delete, and the
admin "Event recordings" settings card, mirroring the minicam web Recordings page.
- [ ] Android: all spec 006 acceptance criteria passing
- [ ] iOS: all spec 006 acceptance criteria passing
- [ ] Checked against a local minicam with recordings on emulator/simulator

Non-goals for M2.5: thumbnails, offline clip cache, trimming/exporting.

## M2.6 - Skeleton loaders (specs/007-skeleton-loaders.md)
Scope: layout-shaped placeholders instead of spinners while dynamic content first loads
(camera list, recordings, admin screens, live view).
- [ ] Android: all spec 007 acceptance criteria passing
- [ ] iOS: all spec 007 acceptance criteria passing

## M2.7 - Digital zoom and clip navigation (specs/008-video-zoom-clip-navigation.md)
Scope: zoom in / zoom out buttons (digital, with drag to pan) on the live view and the recordings
player; player "next" plays the clip below, "back" restarts the clip.
- [ ] Android: all spec 008 acceptance criteria passing
- [ ] iOS: all spec 008 acceptance criteria passing

Non-goals for M2.7: optical PTZ zoom, pinch to zoom, auto-play of the next clip.

## M2.8 - Live fMP4 and audio (specs/009-live-fmp4-audio.md, ADR 0006)
Scope: camera's own H.264/HEVC video as fMP4 with MJPEG fallback, detection boxes drawn by the
app, on-demand live audio gated by `can_listen`, Listen column in Admin permissions.
- [ ] Android: all spec 009 acceptance criteria passing
- [ ] iOS: all spec 009 acceptance criteria passing
- [ ] Checked against a minicam with an HEVC camera on LAN and WAN

Non-goals for M2.8: audio in event recordings (server phase 3), codec/FPS indicator.

## M3 - Comfort
- [ ] Grid view (several cameras at once), mirroring the desktop grid
- [ ] Data-saver mode (lower FPS / pause when on cellular), see SECURITY.md bandwidth note
- [ ] Biometric unlock before showing streams

## Backlog
- Optical PTZ zoom buttons (needs `zoom_in`/`zoom_out` ONVIF actions in the minicam server first)
- Push notifications on detections (needs a backend push design + ADR)
- Store distribution (Play Store / App Store / TestFlight)
- Kotlin Multiplatform for shared networking logic (would supersede part of ADR 0001)
