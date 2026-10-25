# 010 Sub-stream only — Status: Done
## Goal
Follow minicam's switch to always reading the camera's sub-stream (minicam ADR 0002, commit
8142f5a): the camera config no longer has a `stream` field, so the apps drop the Main/Sub picker
from the camera form and stop sending or expecting the field.

## Acceptance criteria
Test names are the same on both platforms (Android: JUnit; iOS: Swift Testing in MinicamKit).
- [x] AC1: A camera config without `stream` is parsed, and one from an older server that still
  sends it is parsed too (the key is ignored) → test: `adminCameras_parsesConfigs`,
  `adminCameras_ignoresLegacyStream`
- [x] AC2: Saving a camera sends no `stream` key → test: `saveCamera_postsNewAndPutsExisting`
- [x] AC3: The camera add/edit form has no Stream picker (Android + iOS) → manual

## Design
Remove `StreamQuality` and `CameraConfig.stream` from both models; the Android JSON parser
already ignores unknown keys and Swift `Codable` skips keys not in `CodingKeys`.

## Security notes
No new input or endpoint; one less field sent to the server.

## Tasks
- [x] Android: model, form, tests
- [x] iOS: model, form, tests

## Out of scope
- Camera name at the top-left of grid cells (minicam 1177ff3): the apps have no grid yet; noted
  on the M3 grid view item.
- Server-side changes with no client impact (single-use stream tokens are already minted per
  connection; lighter off-LAN MJPEG; on-demand remuxing).
