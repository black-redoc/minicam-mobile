# 0017 Settings: app language and minicam updates — Status: Accepted
Date: 2026-11-18

## Context
The apps are English only. Users want Spanish too, chosen in the app. minicam can now update
itself to a newer signed release (minicam ADR 0013); admins should hear about a new release in
the app and start the update from it.

## Decision
- **Settings screen** (Android and iOS), opened from a gear in the camera list's top bar:
  - *Language*: phone language (default), English or Español. Stored on the phone (Android
    `SharedPreferences`, iOS `UserDefaults`); applies at once, no restart.
  - *minicam on this device* (admins): installed version, the latest release, "Update to vX",
    "Check for updates" and the progress of an update.
  - *App*: the app version.
- **Translations**: every user-facing text goes through `tr("English text", args…)`. The English
  text is the key; one Spanish dictionary per platform (`i18n/Spanish.kt`,
  `MinicamKit/Spanish.swift`) with the same keys, generated from one table. Placeholders are
  `%s` / `%d` / `%1$s`. A unit test on each platform fails if a text has no Spanish or the
  placeholders differ. Compose recomposes on a change (the language is snapshot state); SwiftUI
  rebuilds the root view (`.id(language)`). Dates follow the chosen language.
- **Update notice** (admins): after the camera list loads, the app asks `GET /api/system/update`;
  if a newer release is offered, the gear shows a dot and a banner "minicam vX is available"
  opens Settings. Nothing is installed without the admin's tap.
- **Update flow**: `POST /api/system/update {version}`, then poll every 3 s. Failed requests
  while minicam restarts are expected; the update is done when the device answers with the new
  version, failed on its `error` state, on an idle device after 15 s, or after 20 minutes.
- A minicam without the endpoint (404) shows no update section beyond a hint.

## Alternatives considered
- **Android `strings.xml` / iOS `Localizable.strings`**: the platform way, but texts built
  outside views (view models, the push service, MinicamKit) need a context or bundle, Android
  below 13 needs an activity restart, and the keys would differ per platform.
- **Push notification for a new release**: Android only, and minicam would need a new event; the
  in-app notice covers both platforms.
- **Automatic updates**: the admin decides when the device restarts (minicam ADR 0013).

## Consequences
- New texts must be added to the shared table and both dictionaries regenerated; the tests
  catch a missing one.
- Error details the server sends (`detail`) and update helper messages stay in English.
