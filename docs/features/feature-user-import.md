---
id: feature-user-import
title: Importing users from Keycloak
type: feature
status: active
api:
  - endpoint-admin
tags: [identity, migration, m4]
---

# Importing users from Keycloak

## 1. In short

Changing the provider for **people** is not the same as changing it for programs. A person differs
from a service in having a history: accounts, rights, orders. If the platform decides after the
switch that an owner is a new user, they lose access to everything they had — and that is not an
inconvenience but the worst failure available.

So the milestone begins not with the browser sign-in but with the import: first prove that a person
is recognised by the same thing as before.

## 2. What identifies a person today

Checked against the code rather than from memory. A relying service typically does this:

```kotlin
val sub = principal.getClaim("sub", String::class) ?: error("Missing sub in token")
val iss = principal.getClaim("iss", String::class) ?: "unknown"

repo.findByExternalId(sub, iss) ?: repo.create(externalId = sub, provider = iss, …)
```

The key is the **pair `(sub, iss)`**, and both values are taken from the token **verbatim**. The
`provider` column in such a database is literally the string
`https://auth.example.com/realms/main`.

Hence the milestone's main consequence:

> **Matching `sub` is not enough.** Even with identifiers carried over one to one, the token will
> say `iss: https://id.example.com/realms/main`, the pair will not match, `findByExternalId` will
> return `null` — and the relying service **will create a new user**. The owner signs in and sees
> an empty account.

The `?: "unknown"` does not save anyone either: `iss` is always present in a token, it is simply a
different one.

## 3. Why issuing somebody else's issuer is not an option

The temptation is obvious: let the new provider write the old Keycloak address into `iss`, and
nothing changes anywhere.

It must not be done, and that has been settled twice:

* an issuer is a property of an instance, not an alias for somebody else's — a `legacy_iss` field
  was proposed and dropped;
* `oauth4webapi` in next-auth compares `iss` **strictly** against the address the discovery
  document came from. A token with a foreign issuer breaks the browser sign-in — exactly the thing
  this milestone delivers.

So the forged `iss` is forbidden precisely in the milestone that would want it.

## 4. The answer: a stable provider key instead of an address

`iss` is a poor key by nature: it is an **address**, and addresses change. Storing an address in
`provider` ties a person's identity to a DNS name.

Two notions have to be separated:

| | what it is | example |
|---|---|---|
| `iss` in the token | where the token was issued | `https://id.example.com/realms/main` |
| `provider` in the relying service | **whose** user this is | `acme` |

The relying service then matches `(sub, provider)`, where `provider` is a short stable key rather
than a URL. The list of accepted issuers and their mapping to that key lives in configuration:
during a migration it holds both the old provider and the new one — the same shape as a second key
source for signatures.

What has to happen:

1. **the provider**: import users keeping the Keycloak identifier as `sub` — so that the first half
   of the pair matches;
2. **the relying service**: match on `(sub, provider)` with an issuer → key mapping, plus a
   **one-off migration** of existing rows from the old URL to the short key.

The order is mandatory: the relying service's migration ships **before** the new provider starts
issuing user tokens. While the old provider still issues them, the mapping returns the same key and
nothing changes — a safe point to stop at.

## 5. Rules

* A user's identifier from Keycloak is kept verbatim and becomes `sub` in the new provider's
  tokens. Existing people do not get new identifiers of ours.
* The import is **idempotent**: a second run creates no duplicates and changes nothing already
  imported.
* What travels: the identifier, the email, the name, the "email verified" flag, and the link to the
  external provider — the link is how the person is recognised at sign-in.
* Passwords are **not** imported, because there are none: sign-in goes through Google and the magic
  link.
* People disabled in Keycloak are imported disabled rather than skipped: otherwise "sign-in denied"
  silently becomes "enabled".

## 6. How it works

```
operator ── shildik user import --from-keycloak ──▶ CLI
                                                     │
              GET /admin/realms/{realm}/users ───────┤ (Keycloak Admin API, page by page)
              GET …/users/{id}/federated-identity ───┘
                                                     │
              POST :9000/admin/tenants/{t}/users ────▶ shildik
              ◀── created / already there
```

The CLI reads, not the server: a server has no business holding administrator credentials for
somebody else's provider. The operator passes the Keycloak credentials to the command through the
environment, like everything else.

Paging matters more than it looks: Keycloak's `/users` has a default limit, and a request without
paging returns the beginning of the list without saying so. The import would report success while
half the people stayed behind.

## 7. Scenarios

### Scenario: the import keeps the identifier
* **Given:** a Keycloak user `c0b45dd1-…` with the email `owner@example.com`.
* **When:** `shildik user import`.
* **Then:** a user with the same identifier exists here; a token issued to them carries
  `sub: c0b45dd1-…`.

### Scenario: the relying service recognises the person after the switch *(the main one)*
* **Given:** the relying service has a user with that `sub`, and its `provider` migration has run.
* **When:** the person signs in through the new provider and opens their account.
* **Then:** they see **their** data; no new user appeared.
* Until this scenario is green the milestone is not closed.

### Scenario: a second import changes nothing
* **When:** `import` runs twice in a row.
* **Then:** the second run reports "already there" and the state does not change.

### Scenario: a disabled person stays disabled
* **Given:** a Keycloak user with `enabled: false`.
* **Then:** they exist here and cannot sign in.

### Scenario: the import hands out nothing extra
* **When:** `shildik user list`.
* **Then:** no passwords, no tokens, no secrets — none of them were imported.
