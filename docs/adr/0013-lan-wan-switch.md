# 0013 Local and remote address per server, with a switch — Status: Accepted
Date: 2026-11-09

## Context
The apps keep one server address. Moving between the home network (`http://<LAN IP>:8080`) and
Cloudflare Tunnel + Access (`https://<host>`) means signing out and typing the other address.
Changing the address clears the session and the Access token, and sign-out first calls the old
address, so on an unreachable one it waits for the network timeout. In use the owner got stuck
and had to clear the app's data in Android Settings.

minicam decides LAN or WAN for each request (`classify_network`), and a session belongs to a user,
not to an address. Over WAN it also needs a valid Access JWT for that same user. So one session
works over both addresses of the same server.

## Decision
- Each phone keeps **one server with up to two addresses**: a local one (`http`, LAN host) and a
  remote one (`https`), plus which one is active. The existing single address migrates to the
  slot that matches its scheme.
- A **Local / Remote switch** on the camera list and on the login screen changes the active
  address right away. It never calls the old address. The session
  cookie and the Access token are kept; the Access token is only ever sent to the remote address.
  The camera list then reloads. A 401 or an Access redirect is handled as today, for that address only.
- If the other address is not saved yet, the switch asks for it. The local address can come from
  "Find minicam on my network".
- Typing an address that is **not** one of the saved pair means a different server: the session,
  the Access token and the other address are cleared, as today.
- **Sign-out never waits on the network**: the local session is cleared first, and the server
  logout is sent best-effort with a 3 s limit.

## Alternatives considered
- **Automatic LAN-first with WAN fallback**: no tap needed, but it probes the network on every
  open, guesses wrong on captive Wi-Fi, and makes "which path am I on" hard to see. Kept in the
  Backlog; the manual switch is the base it would build on.
- **Several servers (a list)**: covers more than one home, but it is a bigger UI and storage
  change than the problem needs.
- **Only fix sign-out**: removes the hang, but switching still means retyping the address.

## Consequences
- The credential store gets `lan_url`, `wan_url` and `active_network` keys (same Keystore /
  Keychain protection). `server_url` stays readable for the migration.
- The remote address must be the same minicam as the local one; the app cannot check that before
  signing in, so the user is trusted to pair the right two addresses.
- iOS and Android change together.
