---
id: endpoint-admin
title: Admin API — clients, roles and keys
type: endpoint
status: active
---

# Admin API

The management contour: everything a Keycloak admin console is used for. There is no UI — the only
client is the [CLI](../cli.md).

## 1. A separate port is the access model

The admin API listens on the **management port** (`9000` by default), not the public one. No public
ingress is created for it at all.

This is not an "additional measure". A typical Keycloak deployment exposes its console on the same
host that issues tokens, protected by the fact that somebody remembers a password. Splitting by port
gives operations a primitive that cannot be weakened by accident through a role setting: from the
public network the management handles **do not exist**.

Authentication inside the port is a bearer token of an admin client.

**First access.** While the database holds no admin client at all, a bootstrap token is accepted —
from `SHILDIK_BOOTSTRAP_TOKEN`, or a random one printed to the log at start-up. Once the first
admin client is created it **stops working**; while the database is empty it is printed on every
start, so that a pod restart does not leave anybody locked out.

## 2. Handles

| Method | Path | What it does |
|---|---|---|
| `GET` | `/admin/tenants` | list tenants |
| `POST` | `/admin/tenants` | create a tenant (outwards the same thing is a `realm`) |
| `GET` | `/admin/tenants/{tenant}/clients` | list clients |
| `POST` | `/admin/tenants/{tenant}/clients` | create a client; **the secret is returned once** |
| `POST` | `/admin/tenants/{tenant}/clients/{id}/secret` | rotate the secret |
| `PUT` | `/admin/tenants/{tenant}/clients/{id}/import-secret` | accept a given secret (for a migration) |
| `DELETE` | `/admin/tenants/{tenant}/clients/{id}` | delete a client |
| `PUT` | `/admin/tenants/{tenant}/clients/{id}/roles` | replace a client's set of roles |
| `GET` | `/admin/tenants/{tenant}/users` | list users |
| `POST` | `/admin/tenants/{tenant}/users` | import a user, keeping their identifier |
| `PUT` | `/admin/tenants/{tenant}/users/{id}/password` | set a password |
| `GET` | `/admin/tenants/{tenant}/keys` | signing keys and their states |
| `POST` | `/admin/tenants/{tenant}/keys/rotate` | issue a new `active`, move the previous to `retiring` |
| `POST` | `/admin/tenants/{tenant}/keys/{kid}/retire` | retire a key early |

## 3. Rules checked here rather than in the CLI

The invariants live in the domain — the CLI does not duplicate them:

* `clientId` is unique within a tenant;
* a secret is **not stored in the clear** and cannot be read back: a `GET` of the client does not
  return it, and forgetting it means rotating it;
* rotating keys does **not** delete the previous key but moves it to `retiring`; deletion is
  possible only after the cache window has passed;
* a public client must have at least one `redirect_uri`, and gets no secret at all.

## 4. Acceptance scenarios

### Scenario: the secret is shown once
* **When:** `POST /admin/tenants/main/clients` with `clientId: "orders-api"`.
* **Then:** `201` with the secret in the body; a later `GET` of the same client does not contain it.

### Scenario: rotation does not break tokens already issued
* **Given:** a token signed with key `K1`.
* **When:** `POST .../keys/rotate`.
* **Then:** new tokens are signed with `K2`; `K1` is `retiring` and **stays in JWKS**; the token
  signed with `K1` still verifies.

### Scenario: the admin API is unreachable from outside
* **When:** a request to `/admin/...` on the public port.
* **Then:** `404` — the route is not mounted on that port (not `403`: the existence of a management
  contour is not confirmed).

## 5. Code anchors

| What | Where |
|---|---|
| Routes | `server/src/commonMain/.../server/admin/AdminRoutes.kt` |
| The gate (a route-scoped plugin over the whole branch) | `server/src/commonMain/.../server/admin/AdminAuth.kt` |
| The bootstrap rule | `core/src/commonMain/.../core/feature/admin/AdminAccess.kt` |
| Mounting the ports | `server/src/commonMain/.../server/Application.kt` |
