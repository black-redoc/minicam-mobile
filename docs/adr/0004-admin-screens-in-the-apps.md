# 0004 Admin screens in the apps — Status: Accepted
Date: 2026-10-22

## Context
The apps were viewer-only (M1 non-goals: admin, camera settings, AI detection). Admins still had
to open the web dashboard to add a camera, grant a permission or install a detection model. The
user asked for those views in the apps, based on the web pages in minicam (`admin.html`,
`ai_detection.html`, the camera settings modal in `config.js`).

## Decision
- Add three admin-only sections, reached from the camera list menu: **Camera settings**,
  **AI detection** and **Admin**. They call the existing admin endpoints (minicam
  `app/docs/api.md`); no backend change is needed.
- The menu entries show only when `GET /api/auth/me` (or the login response) says `is_admin`.
  This is cosmetic: the server's `require_admin` is the only authorization check.
- Same behavior as the web, except **no `.onnx` upload from the phone** (up to 200 MB over
  mobile data; the catalog and "fetch from URL" cover the need on the server side).
- Error bodies with a string `detail` (400/409/502/503 …) are shown to the user as-is, like the
  web does, so validation messages from minicam reach the admin.
- Platform UI only: no new dependencies on either platform.

## Alternatives considered
- Open the web pages in a browser tab / web view: no extra code, but a second login (cookies are
  not shared with Custom Tabs / `ASWebAuthenticationSession`) and an inconsistent UI.
- Mobile-specific admin endpoints: duplicates the existing JSON API for no gain.

## Consequences
- The apps now write server config and manage accounts: SECURITY.md gains admin rules
  (confirm destructive actions, camera and user passwords never stored or logged).
- The app depends on more of minicam's admin API; contract changes there must be mirrored here.
- Supersedes the "Admin screens" Backlog item; ROADMAP M2 is now "Admin".
