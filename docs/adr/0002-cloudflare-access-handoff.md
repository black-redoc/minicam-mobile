# 0002 Cloudflare Access login via a system-browser handoff — Status: Proposed
Date: 2026-10-21

## Context
On WAN, minicam sits behind Cloudflare Access, and FastAPI requires a valid
`Cf-Access-Jwt-Assertion` whose email matches the minicam session user. Access logs users in with
a web page; a native app can't complete that page by itself. The app also can't read cookies from a
system browser session (Custom Tabs / `ASWebAuthenticationSession`). Service tokens don't carry an
email, so minicam would reject them.

## Decision
Add one endpoint to minicam, `GET /api/mobile/access-handoff?state=<random>`, protected by the
same Access application. The app opens it in the system browser session; after Access login,
minicam verifies the Access JWT (existing `CloudflareAccessVerifier`) and responds `302` to the
fixed URI `minicam://access-callback?state=<state>#token=<Cf-Access JWT>`. The app checks `state`,
stores the token in secure storage and sends it as the `cf-access-token` header (accepted by
Cloudflare Access in place of the `CF_Authorization` cookie) on HTTP requests and WebSocket
handshakes. Contract: [specs/002-backend-mobile-endpoints.md](../specs/002-backend-mobile-endpoints.md).

## Alternatives considered
- Embedded WebView reading the `CF_Authorization` cookie: works for email PIN, but Google and other
  IdPs block embedded webviews, and the app would see the user's IdP password page.
- Access service tokens: no email claim, breaks minicam's identity match.
- Cloudflare WARP on the phone: no app work, but every user must install and enroll WARP.
- `cloudflared access login` transfer flow: undocumented for third-party apps.

## Consequences
- Needs a small, reviewed change in minicam (deviation for that repo — needs approval there).
- Custom scheme can be claimed by another Android app; impact is limited to the Access token
  (first factor only). Android App Links can replace it later.
- Access token lifetime follows the Access application's session duration; on expiry the app
  re-runs the handoff.
