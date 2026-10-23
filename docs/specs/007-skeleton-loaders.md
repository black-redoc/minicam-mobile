# 007 Skeleton loaders — Status: In progress
## Goal
While dynamic content loads for the first time, show grey placeholder shapes with a sweeping
highlight that match the layout about to appear, instead of a spinner or a premature empty
state. Same look on Android and iOS, built from the existing design tokens (no new dependency).

## Acceptance criteria
- [ ] AC1: Camera list: until the first camera request finishes, placeholder camera rows are
  shown instead of the "No cameras" empty state; an error replaces them with its message → manual
- [ ] AC2: Recordings: until the first page loads, a placeholder day label and card of clip rows
  (time, camera, chip, duration/size) replace "Loading…"; a filter change or pull to refresh
  keeps the current list → manual
- [ ] AC3: Admin screens: placeholders replace every first-load spinner: users list, Detection
  notifications and Event recordings cards (Admin), camera list and detection classes (Camera
  settings), model catalog and engine settings (AI detection) → manual
- [ ] AC4: Live view: until the first frame arrives the video area shows a full-size
  placeholder; after a frame was shown, reconnecting keeps the dimmed last frame with the
  spinner → manual
- [ ] AC5: All placeholders share one highlight speed (1.2 s sweep) and colors (`surface2` with
  a `border` highlight), are announced once as "Loading" by TalkBack / VoiceOver, and stay
  static when the system reduces motion (iOS Reduce Motion; Android animator scale 0) → manual
- [ ] AC6: Action spinners stay as they are (Sign in, Save, Fetch, Install, Load more,
  Download, ONVIF search) → manual

## Design
Android: `SkeletonBlock` / `Skeleton` in `ui/Components.kt` (one infinite transition per block,
drawn with a linear gradient), screen-specific placeholder composables next to each screen, form
and list placeholders in `ui/admin/AdminKit.kt`. iOS: `SkeletonBlock` in `Skeleton.swift`
driven by a `TimelineView(.animation)` so every block shares one clock, rows in
`Admin/AdminKit.swift`. "First load finished" for the camera list is a `camerasLoaded` flag on
`AppViewModel` / `AppModel`, reset on sign-out; other screens already keep `null` until loaded.

## Security notes
UI only: no new requests, data or logging.

## Tasks
- [x] Docs: spec, ROADMAP, PLAN
- [ ] Android: skeleton components + all screens (AC1-AC6)
- [ ] iOS: skeleton components + all screens (AC1-AC6)
- [ ] Builds, lint and tests pass on both platforms
- [ ] Visual check on emulator/simulator against a local minicam

## Out of scope
Skeletons for actions (button spinners), the login screen, offline placeholders.
