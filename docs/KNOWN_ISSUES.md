# Known issues

## KI-1 Android app not built or tested yet — Status: Resolved 2026-10-21
Toolchain installed; unit tests, lint, debug and release builds pass. The first real build caught
an XML comment containing `--` in themes.xml (invalid XML), fixed in the same commit.

## KI-2 iOS app target not built yet — Status: Resolved 2026-10-21
Built with Xcode 27.1 + XcodeGen 2.46.0 for the simulator with no Swift warnings; launched on an
iPhone 18 Pro simulator and the login screen renders with the desktop theme.

## KI-3 Backend endpoints missing — Status: Resolved 2026-10-21
`GET /api/me/cameras` and `GET /api/mobile/access-handoff` (spec 002) merged in minicam PR #12.
Servers older than that show an empty camera list and can't complete the WAN login.

## KI-4 LAN traffic is clear text — Status: Open (inherited from minicam)
minicam serves LAN over plain HTTP, so password and session cookie are visible to anyone on the
same Wi-Fi. Mitigation: use the WAN (https) hostname on untrusted networks.

## KI-5 `swift test` flaky with Command Line Tools — Status: Resolved 2026-10-21 (workaround)
With only Command Line Tools selected, the first `swift test` after a clean build sometimes fails
with "plugin for module 'TestingMacros' not found"; a second run passes. With Xcode's toolchain
(`xcode-select -s /Applications/Xcode.app` or `DEVELOPER_DIR=...`) it passes from clean every time.

## KI-6 Android login fails with "Can't reach the server" — Status: Resolved 2026-10-21
Repro: emulator, real minicam on LAN, valid credentials. Server logs `POST /api/auth/login 200`,
app shows "Can't reach the server". Expected: signed in. Cause: `MinicamApi.send` read the
response body after resuming on the caller's dispatcher (Main in `viewModelScope`) →
`NetworkOnMainThreadException`, mapped to the generic error. JVM tests don't enforce this.
Test: `MinicamApiTest.responseBody_isNeverReadOnTheCallersThread`. Fix: the whole request/response
cycle runs in `withContext(Dispatchers.IO)`; verified on the emulator against a real minicam.

## KI-7 WAN fails with 403 "Invalid Cloudflare Access token" — Status: Resolved 2026-10-22 (server config)
Repro: minicam behind Cloudflare Tunnel + Access with `.env` copied from `.env.example` and the
Access lines left as is. Sign in from the app (or the browser) over the tunnel, open a camera.
Expected: video. Actual: `403 {"detail":"Invalid Cloudflare Access token"}` on stream/PTZ; the
browser dashboard still loads (it doesn't check the Access token), which hides the problem.
Cause: `MINICAM_CF_ACCESS_TEAM_DOMAIN` / `MINICAM_CF_ACCESS_AUD` still had the placeholders
(`https://myteam.cloudflareaccess.com`, `changeme-…`), so minicam fetched another team's signing
keys and expected the wrong audience. Not an app bug: the app's token was valid.
Fix: put the real team domain and AUD in `.env` (the server log line
`Rejected Cloudflare Access token: …` shows the token's `aud`/`iss` next to the expected ones;
minicam PR #14) and restart minicam, re-exporting `.env` if it runs under `fastapi dev`.
Prevention: minicam PR #15 refuses to start with any `.env.example` placeholder.
Test: minicam `tests/unit/test_security_settings.py`, `tests/unit/test_cloudflare_access.py`.

## KI-8 PTZ buttons never move the camera — Status: Resolved 2026-10-22
Repro: open a camera with PTZ and tap a direction. Expected: `POST …/{dir}` then `POST …/stop`.
Actual: only `stop` reaches minicam. Cause: the dispatcher cancelled the in-flight
request on every new action, as desktop `ptz.js` does; a browser `fetch` is already on the wire,
but OkHttp and URLSession drop a cancelled call before sending it, so release killed the press.
Fix: `PtzDispatcher` / `PTZDispatcher` never cancel; one request in flight, latest action queued.
Test: `PtzDispatcherTest` (Android), `PTZDispatcherTests` (iOS), `tap_sendsDirectionThenStop`.

## KI-9 Editing permissions in the app revokes "Listen" — Status: Resolved 2026-10-23
Repro: minicam with `can_listen` (PR #21); an admin grants Listen on `/admin`, then toggles View
or PTZ for that user/camera in the app. Expected: Listen stays on. Actual: the app's upsert has no
`can_listen`, the server defaults it to false and revokes it. Test:
`setPermission_sendsCanListen` (both platforms). Fix: spec 009 AC9, every upsert sends
`can_listen` and Admin has a Listen column (a81420f Android, 66a734e iOS).
