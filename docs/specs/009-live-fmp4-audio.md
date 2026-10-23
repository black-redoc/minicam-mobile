# 009 Live fMP4 video and on-demand audio — Status: In progress
## Goal
Watch live cameras with the camera's own compressed video (fMP4) instead of JPEG frames, hear a
camera's audio when allowed, and let admins grant listening, mirroring minicam PRs #20 and #21
([ADR 0006](../adr/0006-live-fmp4-and-audio-in-the-apps.md)).

## Acceptance criteria
Test names are the same on both platforms (Android: JUnit; iOS: Swift Testing in MinicamKit)
unless noted.
- [ ] AC1: The stream token is requested with `?audio=true` only when audio is on, and its
  `video_mime` / `audio_mime` are read (both optional) → test: `streamToken_readsMimes`
- [ ] AC2: The WebSocket URL adds `&format=fmp4` for fMP4 and `&audio=true` for audio →
  test: `streamURL_addsFormatAndAudio`
- [ ] AC3: fMP4 is used when `video_mime` is set and the device can decode it; otherwise, or
  after an fMP4 playback failure, MJPEG → test: `streamFormat_fallsBackToMjpeg` + manual
- [ ] AC4: The init segment's tracks (id, video/audio, timescale, codec config) and a fragment's
  decode times and samples are read; malformed data is rejected, never crashes → tests:
  `fmp4_parsesInitSegment`, `fmp4_parsesFragment`, `fmp4_rejectsMalformedBoxes`
- [ ] AC5: (Android) fragment decode times are rebased so playback starts at 0 → test:
  `fmp4_rebasesDecodeTimes`
- [ ] AC6: Playback catches up to live: faster while more than 1 s is buffered, back to normal
  under 0.3 s → test: `liveCatchUp_speedsUpWhenBehind`
- [ ] AC7: `detections` messages are parsed and their boxes drawn over the video, scaled like the
  contained frame (and zoomed with it) → tests: `detections_parseMessage`,
  `detectionBox_fitsContainedFrame` + manual
- [ ] AC8: A speaker button shows on the live view (regular and fullscreen) only when the
  camera's `can_listen` is true and the session is fMP4 with an `audio_mime`; audio is off every
  time the camera opens; toggling reconnects with or without audio → test:
  `cameras_readsCanListen` + manual
- [ ] AC9: Admin permissions show a **Listen** checkbox per camera and every upsert sends
  `can_listen` (KI-9) → test: `setPermission_sendsCanListen`

## Design
See ADR 0006. Data layer: `StreamToken`, `StreamFormat` choice, `Fmp4` parser, `LiveCatchUp`,
`Detections` + `DetectionBox.fit`. `StreamClient` emits `Session(format, audioMime)`, `Frame`
(MJPEG), `Init(mime, data)`, `Fragment(data)` and `Detections`. Android UI: ExoPlayer on a
`TextureView` inside `AspectRatioFrameLayout`, fed by a pipe `DataSource`; iOS UI: a UIView
backed by `AVSampleBufferDisplayLayer`.

## Security notes
No new hosts or endpoints. Binary parsing bounds every box/sample size against the message and
drops bad messages. Audio is opt-in per view and gated by the server (`can_listen`); the app
never caches audio or video.

## Tasks
- [x] Docs: ADR 0006, spec, ROADMAP M2.8, KI-9
- [ ] Android: models, token/URL, `can_listen` admin column + tests
- [ ] Android: fMP4 parser/rebase, catch-up, detections + tests
- [ ] Android: live player, fallback, boxes, speaker button
- [ ] iOS: models, token/URL, `can_listen` admin column + tests
- [ ] iOS: fMP4 parser, catch-up, detections + tests
- [ ] iOS: live player, fallback, boxes, speaker button
- [ ] Builds, lint and tests pass on both platforms
- [ ] Manual check against a minicam with an HEVC camera (LAN + WAN), audio on/off

## Out of scope
Audio in event recordings (minicam ADR 0001 phase 3, not merged yet), an FPS/codec indicator,
main/sub stream switching, grid view.
