# 0015 Per-phone schedule for detection notifications — Status: Accepted
Date: 2026-11-10

## Context
Admin → Detection notifications has the site-wide switch and cooldown (minicam `config.toml`).
Users want detections only at certain times: days of the week (Mon–Fri, every day) and an hour
range, including overnight ranges (8 PM to 6 AM the next day) and both combined. ADR 0007 left
quiet hours out of scope. Only Android receives pushes today; iOS waits for APNs.

## Decision
- **Filtered on the phone.** `MinicamMessagingService` drops a decrypted detection whose time falls
  outside this phone's schedule; minicam, Web Push and the dashboard's live feed are unchanged.
- **One window:** a set of days plus a start and an end time (30-minute steps). End before start
  means the window runs past midnight and belongs to the day it starts on (Fri 8 PM – 6 AM covers
  Saturday until 6 AM, not Monday until 6 AM). Start equal to end means the whole day.
- **The phone's current time zone**, at the detection's `detected_at` (receive time if missing).
- **Applies to every camera**, whatever its own notification override.
- Edited in Admin → Detection notifications ("Schedule on this phone"), saved at once in the
  credential store (`notification_schedule`), kept on logout. Hidden in builds without Firebase.
- **Android only** for now; iOS gets it with APNs (Backlog).

## Alternatives considered
- **Enforced by minicam:** one schedule for every device and the browser too, but changes three
  code bases (minicam model + API, web admin, apps) and needs a server-side time zone.
- **Several windows:** more flexible (weekday nights + whole weekend), more UI and validation for
  a case none of the requests needed yet.

## Consequences
- Each phone has its own schedule; another phone or the browser still notifies outside it.
- Only admins can edit it, since it lives in the admin screen.
- Pushes still reach the phone and are discarded locally, so relay traffic is unchanged.
