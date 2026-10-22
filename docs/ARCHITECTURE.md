# Architecture

Two independent native apps that share a design and a backend contract, not code
([ADR 0001](adr/0001-native-kotlin-compose-and-swiftui.md)).

```
android/   Kotlin + Jetpack Compose, single :app module
  data/    ServerConfig, MinicamApi (OkHttp), StreamClient (OkHttp WebSocket),
           SessionStore (Android Keystore), AccessHandoff
  ui/      theme/ (desktop tokens), LoginScreen, CamerasScreen, CameraScreen, PtzPad
ios/
  MinicamKit/   Swift package, Foundation only (builds + tests with `swift test` on macOS):
                ServerConfig, MinicamAPI, StreamClient, AccessHandoff, CredentialStore protocol
  MinicamApp/   SwiftUI app target (XcodeGen project.yml): views, theme, Keychain store
```

## Data flow
1. User enters the server base URL. `ServerConfig` validates it: `https://` anywhere, `http://`
   only for LAN hosts (private IPv4, loopback, `*.local`). See SECURITY.md.
2. **WAN (https) hosts only** - Cloudflare Access login ([ADR 0002](adr/0002-cloudflare-access-handoff.md)):
   system browser session (Custom Tabs / `ASWebAuthenticationSession`) opens
   `/api/mobile/access-handoff`; Access authenticates at the edge; minicam redirects to
   `minicam://access-callback?state=…#token=<CF Access JWT>`. The app stores the token and sends
   it as the `cf-access-token` header on every request and WebSocket handshake.
3. `POST /api/auth/login` → the app keeps the `minicam_session` cookie (secure storage) and sends it
   as `Cookie` on later requests.
4. `GET /api/me/cameras` → cameras the user can view, with `can_use_ptz`.
5. Stream: `POST /api/stream/{name}/token` → `WS /ws/stream/{name}?token=…`; each binary message is
   one JPEG frame, decoded and shown. On close/error: fetch a fresh token and reconnect after 3 s
   (same as desktop `stream.js`). The socket closes when the screen leaves the foreground.
6. PTZ: press a direction → `POST /api/ptz/{name}/{dir}`; release → `POST …/stop`. Only one PTZ
   request in flight; a new one cancels the previous (same as desktop `ptz.js`).
7. Any `401` → clear the session, back to login. A Cloudflare redirect/`403` on a WAN host →
   re-run the Access login.

## Backend contract
Existing minicam endpoints: see minicam `app/docs/auth.md`, `stream.md`, `ptz.md`.
New endpoints this app needs: [specs/002-backend-mobile-endpoints.md](specs/002-backend-mobile-endpoints.md).

## Theme (mirrors minicam `app/static/css/main.css`)
| Token | Value | Use |
|---|---|---|
| bg | `#0f172a` | screen background (slate-900) |
| surface | `#1e293b` | cards |
| surface2 | `#263348` | PTZ buttons |
| border | `#334155` | borders |
| accent / accentHover | `#0ea5e9` / `#38bdf8` | primary actions, brand dot |
| danger / success | `#ef4444` / `#22c55e` | badges, logout |
| text / muted | `#f8fafc` / `#94a3b8` | text |
| radius sm / md / lg | 6 / 8 / 12 dp/pt | inputs / buttons / cards, video |

Dark theme only, like the desktop (`color-scheme: dark`). Video is 16:9, black, `contain`.

## Build and delivery
GitHub Actions: `ci.yml` on PRs, `release.yml` publishes installable builds on merge to `master`
([ADR 0003](adr/0003-ci-cd-github-actions.md)).

## Invariants
- No third-party analytics, crash reporting or ads. The app only talks to the configured host and
  its Cloudflare Access team domain.
- Credentials (session cookie, Access token) live only in Android Keystore-backed storage / iOS
  Keychain; never in logs, never in plain preferences.
- The password is never stored.
