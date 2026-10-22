# 002 Backend endpoints for mobile (minicam repo) — Status: In review
## Goal
Give native clients a JSON way to list their cameras and to obtain a Cloudflare Access token.
This is implemented in the **minicam** repo, not here; this spec is the contract the apps code
against.

## Acceptance criteria
- [x] AC1: `GET /api/me/cameras` (session required, any role) returns only cameras the caller has
  `can_view` on:
  `[{"name": "entrada", "can_use_ptz": true}]`; `401` without a session. Same filtering as the
  dashboard (`AuthorizationService.list_viewable_camera_names`).
- [x] AC2: `GET /api/mobile/access-handoff?state=<s>` requires a WAN request with a valid Access JWT
  (`CloudflareAccessVerifier`); responds `302` to
  `minicam://access-callback?state=<s>#token=<the Cf-Access-Jwt-Assertion value>`.
- [x] AC3: `state` must be 32-128 chars of `[A-Za-z0-9_-]`, else `400`. The redirect target is
  fixed server-side (no client-supplied redirect URI).
- [x] AC4: On LAN, or with Access unconfigured / JWT invalid, the handoff returns `403` and never
  redirects.
- [x] AC5: The handoff response has `Cache-Control: no-store` and the token never appears in logs.

Tests: minicam `tests/integration/test_mobile_flow.py`, `tests/unit/test_authorization_service.py`.
Branch `feat/mobile-endpoints`, commit 150ede1.

## Design
New small router in minicam (e.g. `app/routers/mobile.py`), reusing existing dependencies.
Docs: add a section to minicam `app/docs/auth.md` and `cloudflare.md`.

## Security notes
No open redirect (fixed target). The token returned is the one Cloudflare already issued for this
user and app; it grants nothing without a minicam session (ADR 0002).

## Out of scope
Push tokens, admin endpoints.
