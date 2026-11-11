# minicam-mobile
Native mobile clients (Android + iOS) for a self-hosted [minicam](https://github.com/black-redoc/minicam)
instance. A user points the app at their own minicam server (LAN over HTTP, or WAN through
Cloudflare Tunnel + Access), logs in with their minicam account, watches the cameras they are
allowed to see and drives PTZ. The apps never talk to any service other than the user's own
minicam host and their Cloudflare Access login page.

## Current focus
Milestone: M2.16 - Notification schedule · ADR 0015 · Next step: check the schedule on the S23
(overnight Mon–Fri range). M2.14: check the Local / Remote switch on an iPhone. M2.13: scan the
dashboard QR on real phones. Earlier: M2.10 manual AC4/AC5; M2.8 HEVC check; M1 device checks (specs/001, 004).

## Docs
[ROADMAP](ROADMAP.md) · [ARCHITECTURE](ARCHITECTURE.md) · [SECURITY](SECURITY.md) ·
[VALIDATION](VALIDATION.md) · [DEVELOPMENT](DEVELOPMENT.md) · [DEPENDENCIES](DEPENDENCIES.md) ·
[PROGRESS](PROGRESS.md) · [KNOWN_ISSUES](KNOWN_ISSUES.md) · [adr/](adr/)
