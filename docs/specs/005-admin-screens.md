# 005 Admin screens: camera settings, AI detection, admin — Status: In progress
## Goal
An admin can, from Android and iOS, manage cameras, the AI detection engine, users and their
permissions, with the same behavior as the minicam web pages ([ADR 0004](../adr/0004-admin-screens-in-the-apps.md)).

## Acceptance criteria
Test names are the same on both platforms (Android: JUnit; iOS: Swift Testing in MinicamKit).
- [ ] AC1: The camera list menu shows Camera settings / AI detection / Admin only for admins
  (`is_admin` from login or `GET /api/auth/me`) → test: `me_parsesAdminFlag` + manual
- [ ] AC2: Camera settings lists `GET /api/cameras` (name, host) → test: `adminCameras_parsesConfigs`
- [ ] AC3: Add posts the form to `POST /api/cameras`; edit sends it to `PUT /api/cameras/{old name}`
  (name URL-encoded, blank password keeps the current one); delete asks first, then
  `DELETE /api/cameras/{name}` → test: `saveCamera_postsNewAndPutsExisting`, `deleteCamera_sendsDelete`
- [ ] AC4: "Search" runs `GET /api/setup/discover` and a found host can fill the host field →
  test: `discover_parsesCameras` + manual
- [ ] AC5: The form lets the admin pick detection classes from `GET /api/detection/classes` and a
  notifications mode (inherit/on/off); no classes → "No detection backend configured" →
  test: `detectionClasses_parsesClasses` + manual
- [ ] AC6: AI detection shows the model catalog (`GET /api/detection/catalog`, release + assets,
  active badge) and installs an asset with `POST /api/detection/catalog/install` → test:
  `catalog_parsesAssets`, `installCatalogModel_postsName`
- [ ] AC7: AI detection edits backend, confidence, inference FPS and model path
  (`GET/PUT /api/detection/settings`) and can fetch a model from an `https` URL
  (`POST /api/detection/model/fetch`), filling the model path → test:
  `detectionSettings_roundTrip`, `fetchModel_returnsModelPath`
- [ ] AC8: Admin creates users (`POST /api/auth/users`), deletes them after confirming
  (`DELETE /api/auth/users/{id}`), and lists each user with their WAN access and per-camera
  view/PTZ permissions → test: `users_listAndCreate`, `deleteUser_sendsDelete`,
  `userAccess_parsesPermissionsAndWanAccess`
- [ ] AC9: Toggling a permission posts `POST /api/auth/permissions`; toggling WAN access posts
  `POST /api/auth/wan-access` and reverts the switch on failure → test:
  `setPermission_postsBody`, `setWanAccess_postsBody` + manual
- [ ] AC10: Admin edits the global detection notifications (`GET/PUT /api/notifications/settings`)
  → test: `notificationSettings_roundTrip`
- [ ] AC11: A `4xx/5xx` with a string `detail` (e.g. 409 "Email already registered") is shown
  as that message → test: `errorDetail_isReported`

## Design
Data: new admin methods on `MinicamApi` / `MinicamAPI` + models; the existing `check` gains a
`rejected(detail)` error for other status codes with a JSON `detail`. UI: Android adds a
`Screen` state to `AppViewModel` (no navigation library); iOS pushes `AdminRoute` values on the
existing `NavigationStack`. Each section owns a small view model / observable for its data.

## Security notes
Server enforces `require_admin`. Camera passwords and new-user passwords live only in the form
(never saved state, never logged, never prefilled: the server redacts them). Deletes need a
confirmation dialog. Remote catalog text is rendered as plain text. Model fetch URL must be
`https` (server-enforced too). No new hosts contacted by the app.

## Tasks
- [x] Docs: ADR 0004, ROADMAP M2, ARCHITECTURE, SECURITY
- [x] Android: API + models + tests
- [x] Android: Camera settings, AI detection, Admin screens + menu
- [x] iOS: MinicamKit API + models + tests
- [x] iOS: Camera settings, AI detection, Admin views + menu
- [x] Builds, lint and tests pass on both platforms
- [ ] Manual check against a local minicam (emulator/simulator); iOS `ScreenshotTests` attaches
  `ios-camera-settings`, `ios-ai-detection`, `ios-admin` when the test account is an admin

## Out of scope
Uploading `.onnx` files from the phone, push notifications, first-run setup
(`/api/auth/setup`), per-camera health page.
