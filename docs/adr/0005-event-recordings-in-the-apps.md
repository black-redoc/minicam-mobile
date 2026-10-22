# 0005 Event recordings in the apps — Status: Accepted
Date: 2026-10-22

## Context
minicam now records short MP4 clips of detection events (minicam `app/docs/recordings.md`,
commit `fba1699`): a Recordings page (list, player, download, admin delete) and an "Event
recordings" card on the Admin page (switch + storage limit). Recordings/playback was a non-goal
of M1. The user asked for the same feature in the apps.

## Decision
- New milestone **M2.5 - Recordings** ([specs/006](../specs/006-recordings.md)), same scope as the
  web: clip list (newest first, grouped by day, camera filter, "Load more"), player, download,
  admin delete, and the admin "Event recordings" settings card.
- "Recordings" is in the camera list menu for **every** signed-in user; the server returns only
  clips of cameras the user can view (`can_view`) and applies the WAN gate.
- Playback streams `GET /api/recordings/{id}/video` (HTTP `Range`) with the same auth headers as
  every other request (`Cookie`, plus `cf-access-token` on WAN hosts):
  - **Android: Media3 ExoPlayer** (`media3-exoplayer`, `media3-ui`, `media3-datasource-okhttp`),
    fed by the app's existing OkHttp client so headers and the no-redirect rule are shared.
  - **iOS: AVPlayer** (platform), headers passed with the `AVURLAssetHTTPHeaderFieldsKey` asset
    option.
- Download fetches `?download=true` in the app (never through a system download service, which
  would have to be given the session cookie): Android writes it to a file the user picks with the
  Storage Access Framework (`CreateDocument`, no storage permission); iOS saves it to a temporary
  file and opens the share sheet (Save to Files / Photos).

## Alternatives considered
- Android platform `MediaPlayer`/`VideoView` (no dependency): no OkHttp integration, weaker
  seeking and error reporting, controls built by hand. Chosen against by the user.
- Download the whole clip, then play it locally: simpler auth, but slow start and up to ~100 MB
  per clip in memory/cache before the first frame.
- `DownloadManager` (Android): needs the session cookie in its database; rejected.
- Open the web Recordings page in a browser: second login, as in ADR 0004.

## Consequences
- First large Android runtime dependency beyond networking (Media3, ~1-2 MB in release after R8);
  listed in DEPENDENCIES.md, exact version, locked.
- `AVURLAssetHTTPHeaderFieldsKey` is not a documented API; if it ever stops working, the fallback
  is downloading the clip before playing it.
- SECURITY.md gains recording rules (delete confirmation, no credentials outside the app).
