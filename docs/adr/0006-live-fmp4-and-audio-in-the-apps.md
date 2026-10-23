# 0006 Live fMP4 playback and on-demand audio in the apps — Status: Accepted
Date: 2026-10-23

## Context
minicam ADR 0001 (PRs #20, #21) lets a viewer receive the camera's own H.264/HEVC video remuxed
into fragmented MP4 over the same stream WebSocket (`format=fmp4`), ~150 kbit/s instead of
~5-8 Mbit/s of JPEG frames, plus the camera's AAC audio on demand (`audio=true`), gated by a new
per-camera `can_listen` permission. Detection boxes then arrive as JSON for the client to draw.
Clients that don't ask for fMP4 keep getting MJPEG. The apps only spoke MJPEG, so WAN viewing
stayed heavy and there was no audio.

## Decision
- **Format per session.** The stream token now returns `video_mime` / `audio_mime`. The app uses
  fMP4 when `video_mime` is set and the device has a decoder for its codec, MJPEG otherwise, and
  switches to MJPEG for the rest of the view if fMP4 playback fails (like the web).
- **No new dependencies.** A small fMP4 box parser in each app (init: tracks, timescales, codec
  config; fragments: `tfdt` and samples).
  - **Android:** Media3 ExoPlayer (already used for recordings) plays the socket's bytes as one
    endless fragmented MP4 through a custom `DataSource` and `FragmentedMp4Extractor`, with small
    buffers. Decode times are rebased so the first fragment starts at 0 (ExoPlayer would
    otherwise wait until the clock reaches the camera's timestamps).
  - **iOS:** samples become `CMSampleBuffer`s for an `AVSampleBufferDisplayLayer` and an
    `AVSampleBufferAudioRenderer` on one `AVSampleBufferRenderSynchronizer`.
  - Both keep playback near live by playing faster while more than ~1 s is buffered (a new
    viewer starts at the latest keyframe, up to a GOP behind).
- **Detection boxes** are drawn by the app over the video (fMP4 only; MJPEG has them burned in).
- **Audio, same rules as the web:** a speaker button only when the camera has `can_listen` for
  the user and the token has an `audio_mime`; off every time a camera is opened; turning it on or
  off reconnects (`?audio=true` on the token, `&audio=true` on the socket).
- **Admin:** the per-camera permissions get a **Listen** column, sent with every upsert (the
  server resets a missing `can_listen` to false, KI-9).

## Alternatives considered
- **Android: MediaCodec + AudioTrack by hand:** full control
  of latency, but A/V sync and decoder quirks become ours. ExoPlayer already handles both.
- **Local HLS server feeding AVPlayer (iOS):** standard player, but seconds of latency and an
  HTTP server inside the app.
- **WebRTC / third-party players (VLC, ffmpeg-kit):** large dependencies, and the server only
  speaks fMP4/MJPEG.

## Consequences
- WAN viewing uses the camera's own bitrate; MJPEG stays as fallback and for old servers.
- The apps now parse binary data from the server: the parsers bound every size against the
  message and drop malformed messages instead of crashing (SECURITY.md).
- The iOS app plays audio through `AVAudioSession` (`.playback`) only while audio is on.
