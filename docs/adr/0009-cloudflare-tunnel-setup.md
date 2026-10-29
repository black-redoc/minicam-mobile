# 0009 Cloudflare Tunnel setup from the app — Status: Accepted
Date: 2026-10-29

## Context
Reaching minicam from outside the LAN needs a Cloudflare Tunnel, a DNS record and a Cloudflare
Access application (minicam `app/docs/cloudflare.md`). Today an admin does it over SSH and in
the Cloudflare dashboard. We want the app to do it: the admin, on the LAN, pastes a Cloudflare
API token and picks a hostname; the app sets everything up and the device becomes reachable at
`https://<hostname>`. Backend side: minicam ADR 0008.

## Decision
- **Where:** Admin screen → "Remote access", shown only on a LAN connection (minicam rejects it
  from WAN).
- **Cloudflare API from the app** (`https://api.cloudflare.com/client/v4`), with an API token the
  admin creates (the screen lists the permissions: Account · Cloudflare Tunnel: Edit, Access:
  Apps and Policies: Edit, Access: Organizations, Identity Providers, and Groups: Read; Zone ·
  DNS: Edit, Zone: Read). The token lives only in memory for the duration of the setup: never
  stored, logged or sent to minicam.
- **Steps**, each idempotent where the API allows (existing tunnel / record / app with the same
  name is reused):
  1. verify the token; list accounts and zones (picker when more than one);
  2. read the Zero Trust organisation (team domain); if there is none, stop and explain how to
     enable Zero Trust;
  3. create the tunnel `minicam-<device hostname>` (`config_src: cloudflare`), read its token;
  4. set its ingress: `<hostname>` → `http://127.0.0.1:8080`, everything else → 404;
  5. create a proxied CNAME `<hostname>` → `<tunnel id>.cfargotunnel.com`;
  6. create a reusable Access policy allowing the admin's email and a self-hosted Access
     application for `<hostname>`; read its AUD tag;
  7. `POST /api/system/tunnel` to minicam with `{tunnel_token, hostname, team_domain, aud}` and
     poll `GET /api/system/tunnel` until `running` or `error`.
  Then the app offers to add `https://<hostname>` as the WAN address.
- **Users:** the Access policy starts with the admin's email; giving another user WAN access in
  the Admin screen adds their email to that policy while a token is entered (otherwise the
  screen says to add it in Cloudflare).
- **Shared logic** (request building, response parsing, step state machine, validation) lives in
  testable code on both platforms (`data/CloudflareSetup`, `MinicamKit/CloudflareSetup`); the
  HTTP layer is the existing transport.

## Alternatives considered
- **Device calls the Cloudflare API:** the Pi would hold an account-wide token. Rejected
  (minicam ADR 0008).
- **Store the API token for later changes:** convenient for user changes, but an account-wide
  credential on the phone; rejected, the admin pastes it again when needed.
- **OAuth with Cloudflare:** no public OAuth flow for third-party apps covers these APIs.

## Consequences
- Invariant change: besides the configured host and its Access team domain, the app talks to
  `api.cloudflare.com`, and only during this setup.
- The admin needs a domain on Cloudflare and Zero Trust enabled (free plan).
- Partial failures leave created objects in the account; re-running the setup reuses them.
