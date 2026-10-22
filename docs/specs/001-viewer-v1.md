# 001 Viewer v1 — Status: In progress
## Goal
A minicam user can, from Android and iOS, connect to their server (LAN or WAN), log in, see the
cameras they're allowed to view, watch the live stream and drive PTZ.

## Acceptance criteria
Test names are the same on both platforms (Android: JUnit method names; iOS: Swift Testing).
- [ ] AC1: `https` URLs are accepted; `http` only for private IPv4, loopback and `*.local`; any
  other input is rejected → test: `serverConfig_acceptsHttpsAndLanHttp_rejectsPublicHttp`
- [ ] AC2: Login posts `{email,password}` to `/api/auth/login` and keeps the `minicam_session`
  cookie; `401` surfaces "Invalid email or password" → test: `login_storesSessionCookie`,
  `login_unauthorized_reportsInvalidCredentials`
- [ ] AC3: Requests carry the session cookie and, if present, the `cf-access-token` header →
  test: `requests_carrySessionAndAccessHeaders`
- [ ] AC4: Any `401` after login clears the session → test: `unauthorized_clearsSession`
- [ ] AC5: Camera list parses `/api/me/cameras` → test: `cameras_parsesViewableCameras`
- [ ] AC6: Stream URL is `ws(s)://host/ws/stream/{name}?token=…` with the name URL-encoded and the
  scheme matching the server → test: `streamUrl_matchesSchemeAndEncodesName`
- [ ] AC7: PTZ sends `POST /api/ptz/{name}/{action}` for up/down/left/right/stop only →
  test: `ptz_postsAction`
- [ ] AC8: Access handoff builds the handoff URL with a random `state`, accepts only a callback
  with the same `state`, and extracts the token from the fragment → test:
  `accessHandoff_buildsUrlWithState`, `accessHandoff_rejectsStateMismatch`,
  `accessHandoff_extractsTokenFromFragment`
- [ ] AC9: The stream closes when the camera screen leaves the foreground and reconnects with a
  fresh token 3 s after an unexpected close → test: `reconnectPolicy_waitsThreeSeconds` + manual
- [ ] AC10: UI uses the desktop theme tokens (ARCHITECTURE.md) → manual check

## Design
ARCHITECTURE.md; ADR 0001, ADR 0002. Depends on spec 002 (backend) for AC5 and AC8 end to end.

## Security notes
SECURITY.md transport rules and checklist. Password never stored. `allowBackup=false`.

## Tasks
- [x] Android skeleton, theme, screens, data layer, unit tests
- [x] iOS MinicamKit + tests (`swift test` passing), SwiftUI app sources, project.yml
- [x] Android tests/lint/debug+release build passing (deps bumped to latest stable)
- [x] iOS app builds in Xcode (simulator), login screen checked visually
- [ ] Backend spec 002 merged in minicam (implemented on `feat/mobile-endpoints`)
- [ ] Manual device checks (LAN, WAN)

## Out of scope
Admin, camera settings, AI detection, grid view, notifications (ROADMAP.md).
