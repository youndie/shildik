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
* `apply` is idempotent: a second run with the same file changes nothing.

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
