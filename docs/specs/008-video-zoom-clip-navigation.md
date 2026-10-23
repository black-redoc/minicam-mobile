# 008 Digital zoom and clip navigation — Status: In progress
## Goal
Look closer at a part of the picture with zoom in / zoom out buttons on the live view and the
recordings player, and step through clips from the player: "next" plays the clip below in the
list, "back" starts the current clip over.

## Acceptance criteria
Test names are the same on both platforms (Android: JUnit; iOS: Swift Testing in MinicamKit).
- [ ] AC1: Zoom is digital (the picture is scaled in the app, the camera is not moved) and goes
  1× → 1.5× → 2× → 3× → 4× and back; "Zoom in" is disabled at 4×, "Zoom out" at 1× → test:
  `videoZoom_stepsBetweenLimits` + manual
- [ ] AC2: While zoomed, dragging the picture pans it; it never shows past the picture's edges,
  and zooming out pulls the pan back inside the new limits → test: `videoZoom_panIsClamped`
- [ ] AC3: The live view has glass "Zoom in" / "Zoom out" buttons in the regular and the
  fullscreen layout; zoom and pan carry over between them and reset when leaving the camera →
  manual
- [ ] AC4: The recordings player has the same buttons (shown with the player's controls), inline
  and in fullscreen; zoom resets when another clip starts → manual
- [ ] AC5: The player's "next" button plays the clip below the current one in the list (the
  list is newest first, so the next older clip); on the last loaded clip it loads the next page
  first; it is disabled when there is no clip after the current one → test:
  `nextRecording_isTheOneBelow` + manual
- [ ] AC6: The player's "back" button seeks the current clip to 0:00 and keeps it playing; it
  never switches to another clip → manual

## Design
Pure state in the data layer: `VideoZoom` (scale steps, pan as a fraction of the view size so
it survives layout changes, clamped to `±(scale - 1) / 2`) and `nextRecording(items, current)`.
Android: live view scales the frame with `graphicsLayer`; the player inflates a `PlayerView`
with a `TextureView` surface so the surface can be scaled and moved, and wraps the ExoPlayer in
a `ForwardingPlayer` whose `seekToPrevious` restarts the clip and `seekToNext` opens the next
one. iOS: `scaleEffect` + `offset` on the frame / `AVPlayerLayer`, previous/next buttons in the
custom controls next to play/pause.

## Security notes
No new requests: zoom is local; "next" uses the existing recordings list / paging endpoint.

## Tasks
- [x] Docs: spec, ROADMAP M2.7, Backlog entry for optical PTZ zoom
- [ ] Android: `VideoZoom` + `nextRecording` + tests
- [ ] Android: live view zoom, player zoom, previous/next
- [ ] iOS: `VideoZoom` + `nextRecording` + tests
- [ ] iOS: live view zoom, player zoom, previous/next
- [ ] Builds, lint and tests pass on both platforms
- [ ] Manual check on emulator/simulator

## Out of scope
Optical PTZ zoom (the minicam server only accepts up/down/left/right/stop; Backlog), pinch to
zoom, auto-playing the next clip when one ends.
