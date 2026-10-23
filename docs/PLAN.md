# minicam-mobile
Native mobile clients (Android + iOS) for a self-hosted [minicam](https://github.com/black-redoc/minicam)
instance. A user points the app at their own minicam server (LAN over HTTP, or WAN through
Cloudflare Tunnel + Access), logs in with their minicam account, watches the cameras they are
allowed to see and drives PTZ. The apps never talk to any service other than the user's own
minicam host and their Cloudflare Access login page.

## Current focus
Milestone: M2.7 - Digital zoom and clip navigation · Spec: specs/008-video-zoom-clip-navigation.md
· Next step: implement on Android and iOS. Still open: M1 device checks (specs/001, 004), M2,
M2.5 and M2.6 manual checks (005, 006, 007).

## Docs
[ROADMAP](ROADMAP.md) · [ARCHITECTURE](ARCHITECTURE.md) · [SECURITY](SECURITY.md) ·
[VALIDATION](VALIDATION.md) · [DEVELOPMENT](DEVELOPMENT.md) · [DEPENDENCIES](DEPENDENCIES.md) ·
[PROGRESS](PROGRESS.md) · [KNOWN_ISSUES](KNOWN_ISSUES.md) · [adr/](adr/)
