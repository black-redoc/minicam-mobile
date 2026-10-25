# 011 Live view time limit — Status: In progress
## Goal
Save data transfer: each time a camera is opened, its live stream runs for at most 30 seconds.
When the time is up the app closes the stream connection and shows that viewing ended; to watch
again the user goes back and opens the camera again.

## Acceptance criteria
Test names are the same on both platforms (Android: JUnit; iOS: Swift Testing in MinicamKit).
- [x] AC1: A new budget has 30 s left and counts down only while running → test:
  `viewingBudget_countsOnlyWhileRunning`
- [x] AC2: Time already used is kept across pauses (app in background, audio toggled, MJPEG
  fallback), so reconnecting never resets the 30 s → test: `viewingBudget_keepsUsedTimeAcrossPauses`
- [x] AC3: Once the 30 s are used the budget is expired and stays at 0 → test:
  `viewingBudget_expiresAtLimit`
- [ ] AC4: When the budget expires the live view closes the stream connection (MJPEG or fMP4,
  with or without audio), hides the speaker button and shows "Viewing ended" over the video with
  a hint to reopen the camera; the status badge reads "Ended" (Android + iOS) → manual
- [ ] AC5: Going back to the camera list and opening the camera again starts a new 30 s → manual

## Design
A small `ViewingBudget` (Android `data/`, iOS `MinicamKit`) with an injectable monotonic clock:
`start()`, `stop()`, `remaining`, `expired`. The camera screen creates one per open; a timer runs
it only while the screen is in the foreground (the same rule that already gates the stream), and
when it expires the stream task is cancelled, which closes the HTTP connection. Time counts from
when the stream starts connecting, so slow connects are counted too. PTZ stays usable.

## Security notes
No new input, endpoint or host. Lowers the bandwidth cost noted in SECURITY.md.

## Tasks
- [x] Android: `ViewingBudget` + tests, camera screen
- [x] iOS: `ViewingBudget` + tests, camera view
- [x] Docs: PROGRESS, ROADMAP, PLAN, SECURITY note

## Out of scope
- A configurable limit or a setting to turn it off
- A "watch again" button in the live view (the user reopens the camera)
- Limits for the recordings player
