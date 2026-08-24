---
id: feature-client-admin
title: Managing clients and roles
type: feature
status: active
api:
  - endpoint-admin
tags: [identity, admin, mvp]
---

# Managing clients and roles

## 1. In short

Somebody has to create clients, grant them roles and rotate secrets. In Keycloak that is the admin
console; here it is a CLI over an admin API on a separate port.

The goal is best stated through the predecessor's failure: a Keycloak configuration assembled by
clicking exists **only** in its database — no export, no import, no way to raise the contour again.
So `export`/`apply` here is not a convenience but part of the definition of done: the
configuration has to travel to git and be applied back with one command.

## 2. Rules

* `clientId` is unique within a tenant.
* A secret is visible **once** — at creation and at rotation. Forgotten means rotated.
* A role is a string. A format like `<who is called>:<who calls>` is a convention of whoever uses
  it, not a constraint of shildik: it does not interpret roles.
* The management handles live on the management port and are never published outwards.
* `export` contains no secrets — placeholders stand in their place.
* A **public client** (a browser one) gets no secret at all, and `rotate-secret` does nothing for
  it: there is nothing to rotate. It must have at least one `redirect_uri` — without one a sign-in
  is impossible, and that is an error at creation rather than "not configured yet".
* A client carries the **resources it may hold a token for** — what ends up in `aud`
  ([protocol-oidc-subset §2](../api/protocol-oidc-subset.md)). Empty is allowed and means its tokens
  carry no `aud` at all, which is how every client behaved before the field existed; a resource
  server that checks the audience refuses such a token. It has its own address rather than being a
  field of a general update: changing it changes which services will accept this client's tokens.
* A client also carries the **permissions it may hold** — what ends up in `scope`. Empty means its
  tokens carry no `scope` claim at all, which is how every client behaved before the field existed;
  a resource server built on OAuth refuses such a token and names the scope it wanted. Its own
  address again, and for the neighbouring reason: the audience decides who will take the token, this
  decides what it may do once taken.
* `apply` is idempotent: a second run with the same file changes nothing. It compares roles,
  audiences and scopes **separately** — a client whose several drifted used to have all but the
  first left as they were, with `apply` reporting success over an instance still unlike the file.

## 2a. There is no dynamic client registration, on purpose

[RFC 7591](https://www.rfc-editor.org/rfc/rfc7591) is not implemented and `registration_endpoint` is
absent from discovery. A client that expects to register itself — the MCP specification assumes one
will, and its clients do — refuses to connect and says so in those words. The answer is to create it
here first, with its `redirect_uri`, and hand it the `client_id`.

Two reasons, and the first is the one that decides.

**Registration would not remove the administrator from the loop.** It hands a client an id. It does
not hand it an audience or a scope, and here a token is worth nothing without both: a self-registered
client would receive tokens with no `aud` and no `scope`, which every resource server that checks
either refuses. The manual step does not disappear, it moves from "create a client" to "grant it what
it needs" — and that is the half that is meant to require a person. Granting a program the right to
act somewhere is not a decision to automate away.

**A client that created itself lives only in the database.** It is not in the file in git, `apply` on
an empty instance does not bring it back, and `plan` reports it as an extra to be removed. That is a
hole in the property §1 calls the definition of done — the configuration travels to git and comes
back with one command — and it is the property this whole surface exists for.

If this is ever revisited, the shape that would not break the above: a per-tenant switch, loopback
redirect addresses only, and clients born with no audiences and no scopes. That is registration as a
way to skip the typing, not as a way to skip the granting.

## 3. How it works

```
operator ── shildik client create orders-api --role orders:read ──▶ CLI
                                                                     │
                             POST :9000/admin/tenants/main/clients ──┘
                             ◀── 201 { clientId, secret }   ← the secret is visible here and nowhere else
```

Validation lives on the server. The CLI builds a request and prints the answer; uniqueness and
format rules belong to the domain.

## 4. Code anchors

| What | Where |
|---|---|
| Use cases | `core/src/commonMain/.../core/feature/admin/AdminUseCases.kt` |
| Routes | `server/src/commonMain/.../server/admin/AdminRoutes.kt` |
| CLI commands | `cli/src/commonMain/.../cli/Commands.kt` |
| `export`/`apply` | `cli/src/commonMain/.../cli/ExportApply.kt` |

## 5. Scenarios

### Scenario: a client is created with its roles in one call
* **When:** `shildik client create orders-api --role orders:read --role orders:write`.
* **Then:** the client exists, both roles are granted, the secret is printed.

### Scenario: creating the same clientId again is refused
* **Then:** a non-zero exit code and a clear message; the existing client is **not changed**
  (otherwise `create` quietly becomes `update` and wipes its roles).

### Scenario: the secret cannot be read back
* **When:** `shildik client list`, and a `GET` of the client after creation.
* **Then:** no output contains the secret.

### Scenario: export and apply onto an empty instance
* **Given:** a configured instance and an empty second one.
* **When:** `shildik export -f conf.json`, then `shildik apply -f conf.json` against the second.
* **Then:** tenants, clients and roles match; secrets are supplied separately.
* This is the acceptance of the whole milestone: precisely what the console-configured predecessor
  could not do.

### Scenario: apply is idempotent
* **When:** `apply` runs twice with the same file.
* **Then:** the second run changes nothing and does not fail.
