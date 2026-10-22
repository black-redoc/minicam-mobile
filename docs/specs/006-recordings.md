# 006 Event recordings — Status: In progress
## Goal
Any user can browse, play and download the detection clips of the cameras they can view, and an
admin can delete clips and set the recording switch and storage limit, like the minicam web
Recordings page and Admin card ([ADR 0005](../adr/0005-event-recordings-in-the-apps.md)).

## Acceptance criteria
Test names are the same on both platforms (Android: JUnit; iOS: Swift Testing in MinicamKit).
- [ ] AC1: The camera list menu has "Recordings" for every signed-in user → manual
- [ ] AC2: The list loads `GET /api/recordings?limit=30` (plus `camera=` when filtered, URL-encoded)
  and parses `items` + `next_before` → test: `recordings_parsesPage`, `recordings_sendsFilters`
- [ ] AC3: "Load more" passes the previous `next_before` as `before`; it is hidden when
  `next_before` is null → test: `recordings_sendsFilters` + manual
- [ ] AC4: Clips are grouped by local day ("Today", "Yesterday", then the date), each row shows
  the local time, camera, classes, duration `m:ss` and size (KB/MB/GB) → test:
  `recordingStart_parsesIsoTimestamps`, `clipDuration_formats`, `clipSize_formats`,
  `recordingDays_groupByLocalDay`
- [ ] AC5: Tapping a clip plays `/api/recordings/{id}/video` with the session cookie (and
  `cf-access-token` on WAN) → test: `recordingVideoRequest_isAuthorized` + manual
- [ ] AC6: Download saves `?download=true` (Android: file picked by the user; iOS: share sheet)
  → test: `recordingVideoRequest_isAuthorized` + manual
- [ ] AC7: Admins can delete the open clip after confirming (`DELETE /api/recordings/{id}`;
  a 404 counts as deleted) → test: `deleteRecording_sendsDelete`
- [ ] AC8: The Admin screen has an "Event recordings" card: switch, storage limit (1/5/10/50 GB,
  limits that don't fit shown struck through with "Not enough space" and not selectable), usage
  line, `GET/PUT /api/recordings/settings`, server `detail` shown on 400 → test:
  `recordingSettings_roundTrip`, `storageBytes_formats`
- [ ] AC9: The player has a fullscreen mode like the live view (spec 004): landscape, system bars
  hidden, playback continues without restarting, back / exit button leaves it (Android: the
  Media3 controls' fullscreen button; iOS: a glass button over the video) → manual

## Design
Data: `recordings`/`deleteRecording`/`recordingSettings`/`saveRecordingSettings`/
`recordingVideoRequest` on `MinicamApi` / `MinicamAPI` + models + pure formatting helpers.
UI: Android `Screen.RECORDINGS` with a Media3 `PlayerView`; iOS `AppRoute.recordings` on the
`NavigationStack` with `VideoPlayer`. The admin card lives in the existing Admin screen.

## Security notes
Server enforces `can_view`, the WAN gate and `require_admin`; the app never filters by
permission. Delete asks first. Auth headers go only to the configured host (player data source
and download use the app's own client). Downloaded files are written only where the user chose
(Android) or to the app's temp dir, removed after sharing (iOS).

## Tasks
- [x] Docs: ADR 0005, ROADMAP M2.5, ARCHITECTURE, SECURITY, DEPENDENCIES
- [x] Android: API + models + formatting + tests
- [x] Android: Media3 dependency, Recordings screen + menu, admin card
- [x] iOS: MinicamKit API + models + formatting + tests
- [x] iOS: Recordings view + menu, admin card
- [x] Builds, lint and tests pass on both platforms
- [x] Fullscreen player on Android and iOS (AC9)
- [ ] Manual check against a local minicam with recordings (emulator/simulator); iOS
  `ScreenshotTests` attaches `ios-recordings`

## Out of scope
Recording-specific push notifications, thumbnails, offline cache of clips, trimming/exporting.
