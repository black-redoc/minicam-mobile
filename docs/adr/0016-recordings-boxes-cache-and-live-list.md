# 0016 Recordings: detection boxes, on-device caches and a live clip list — Status: Accepted
Date: 2026-11-18

## Context
- Recorded clips show no YOLO boxes: minicam remuxes them without re-encoding. minicam ADR 0012
  now saves each clip's boxes as a track served by `GET /api/recordings/{id}/detections`.
- Every visit to Admin → AI detection fetches the model catalog again, a slow request (the server
  reads the minicam-models manifest).
- Every clip is downloaded again each time it is played.
- The list pages with a "Load more" button and doesn't learn about new clips. A detection push
  arrives when YOLO confirms the detection, but the clip is only listed once it is finished
  (post-roll, up to 5 minutes).

## Decision
- **Boxes**: when a clip is selected the app fetches its track and draws the frame at the playback
  position over the player, with the live view's overlay. A frame holds for at most 3 s; an older
  server (404) or a failed request means no boxes.
- **Model catalog**: kept in memory for the session and the server; fetched again only after an
  install or on sign-out. No disk copy.
- **Clip cache**: Media3 `SimpleCache` in the app's private cache directory, LRU, 512 MB, keyed by
  the clip path (`/api/recordings/{id}/video`) so LAN and remote addresses share it. Clips are
  immutable once listed. A deleted clip leaves the cache; sign-out empties it.
- **List**: infinite scroll; the next page loads near the end of the list, also after a delete
  shortens it. The same detection push (no new message type) records a pending detection in the
  app; while the Recordings screen is open it asks for the newest page every 5 s until a clip of
  that camera covers the detection time, then adds the new clips on top. A pending detection
  expires after 7 minutes (recording off, or another camera filter).
- Android only; iOS is in the Backlog.

## Alternatives considered
- **Boxes burned into the clip** on the server: CPU re-encode on a Pi (minicam ADR 0012).
- **A second "clip ready" push**: exact, but a new message type in minicam and the relay; the user
  chose to reuse the detection push and wait.
- **Delaying the detection push until the clip is saved**: alerts would arrive minutes late.
- **Numbered pages**: the server pages with a cursor (`before`), not offsets.

## Consequences
- Up to 512 MB of clips on the phone, in app-private storage (not backed up, cleared on sign-out).
- A new clip shows up within about 5 s of being saved while the screen is open; otherwise on the
  next visit.
- The catalog's "installed" marks can be stale if another admin installs a model meanwhile, until
  the next sign-in or install from this phone.
