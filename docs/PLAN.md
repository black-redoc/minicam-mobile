# minicam-mobile
Native mobile clients (Android + iOS) for a self-hosted [minicam](https://github.com/black-redoc/minicam)
instance. A user points the app at their own minicam server (LAN over HTTP, or WAN through
Cloudflare Tunnel + Access), logs in with their minicam account, watches the cameras they are
allowed to see and drives PTZ. The apps never talk to any service other than the user's own
minicam host and their Cloudflare Access login page.

## Current focus
Milestone: M1 - Viewer v1 · Spec: specs/001-viewer-v1.md (CI/CD: specs/003-ci-cd.md) · Next
step: merge the CI/CD PR and check the first release; merge minicam branch `feat/mobile-endpoints`
(spec 002), then test both apps end to end against a real server on LAN and through the tunnel.

## Docs
[ROADMAP](ROADMAP.md) · [ARCHITECTURE](ARCHITECTURE.md) · [SECURITY](SECURITY.md) ·
[VALIDATION](VALIDATION.md) · [DEVELOPMENT](DEVELOPMENT.md) · [DEPENDENCIES](DEPENDENCIES.md) ·
[PROGRESS](PROGRESS.md) · [KNOWN_ISSUES](KNOWN_ISSUES.md) · [adr/](adr/)
