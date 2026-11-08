# 0007 Push notifications on detections (Android via FCM, relayed by minicam-admin) — Status: Accepted
Date: 2026-10-27

## Context
minicam already turns detections into notification events (2 consecutive hits, per-camera
cooldown, global / per-camera switch) and resolves recipients from `can_view` at send time
(`app/services/notifications/`). It delivers them over SSE and Web Push to browsers; its docs
reserve native FCM/APNs delivery for the apps. The apps only show detections while open.

Constraints:
- Each minicam is a Raspberry Pi in a customer's home; the customer may have root (minicam-admin
  SECURITY.md). An FCM service-account key on the Pi could send pushes to every install of the app.
- iOS push needs APNs, which needs the paid Apple Developer Program. We are not enrolled yet.
- Invariant: the app only talks to the minicam host and its Access team domain; no third-party SDKs
  beyond the allowlist in DEPENDENCIES.md.

## Decision
- **Android only for now.** iOS (APNs, no Firebase SDK) is deferred until we join the Apple
  Developer Program; the relay contract below is platform-neutral so iOS plugs in later.
- **Relay in minicam-admin.** The Firebase service-account key lives only as a secret of the
  minicam-admin Worker. minicam calls new Device API routes with its existing device credential
  (minicam-admin ADR 0003):
  - `POST /api/device/push-tokens` / `DELETE`: register / drop an FCM token for one of the site's
    minicam users. The relay stores `push_tokens(site_id, minicam_user, platform, token)`.
  - `POST /api/device/push`: `{messages: [{token, data}]}`. The relay sends only to tokens
    registered for the calling site, as FCM HTTP v1 high-priority **data** messages, rate-limited
    per site, and returns the tokens FCM reports as unregistered so minicam deletes them.
- **minicam is the authority on who receives what.** The app registers its token with minicam
  (session + WAN gate, like Web Push subscriptions); minicam stores it per user, forwards it to the
  relay and, on each event, sends one message per token of each recipient (`can_view`, resolved at
  send time). Logout, a revoked user or a lost `can_view` stops pushes right away.
- **Recipients are the same as Web Push:** every active user with `can_view` on the camera. A
  subscription (or `wan_access`) is not required to receive pushes.
- **End-to-end encrypted payload**, like Web Push: on opt-in the app creates a random AES-256 key
  (Android Keystore) and registers it with the token. minicam encrypts the existing event JSON
  (`camera`, `classes`, `detected_at`) with AES-256-GCM; Google and the relay only see ciphertext.
  The app decrypts in its `FirebaseMessagingService` and shows a local notification; a tap opens
  that camera's live view.
- **Smallest Firebase footprint.** Only `com.google.firebase:firebase-messaging` (pinned). No
  `google-services` Gradle plugin (not on the allowlist): `FirebaseApp` is initialized by hand from
  build-time values (`MINICAM_FIREBASE_*`); without them push is compiled out of the UI. No
  Analytics, auto-init off, token created only after the user opts in.
- **Opt-in per phone.** A "Detection notifications on this phone" switch, off by default; turning
  it on asks for `POST_NOTIFICATIONS` (Android 13+).

## Alternatives considered
- **minicam sends straight to FCM:** simplest, but puts the project-wide key on customer hardware.
- **Separate relay repo/Worker:** would duplicate site credentials and phone data already in
  minicam-admin.
- **Firebase SDK on iOS too:** one sender API, but the first third-party package on iOS and it still
  needs APNs (paid program).
- **UnifiedPush / ntfy:** no Google, but users must install and run a distributor app.
- **Plain FCM `notification` payload:** shown by the OS without app code, but Google reads it.

## Consequences
- Android adds Google endpoints (FCM, Firebase Installations) to the hosts the app talks to; the
  ARCHITECTURE.md invariant gets an explicit, opt-in exception. Needs Google Play Services.
- Push works only when the site is linked to minicam-admin (device credential set); otherwise the
  switch is disabled with a reason.
- A rooted Pi can push ciphertext only to tokens registered under its own site, within its rate
  limit. It cannot reach other sites' phones.
- Three repos change: minicam (token store, sender), minicam-admin (relay), minicam-mobile (Android
  client).
