---
id: feature-service-auth
title: Service-to-service authorization
type: feature
status: active
api:
  - protocol-oidc-subset
tags: [identity, oauth2, mvp]
---

# Service-to-service authorization

## 1. In short

A service gets a token with which it proves to a neighbour that it is itself and that it is allowed
to call. There is no user anywhere in this chain: what authenticates is a program, not a person.

This was shildik's first milestone and the **safest part of OAuth2**: no redirects, no sessions, no
cookies, no PKCE — that is, not one of the places where a hand-written provider usually turns into
a hole. It still carries real load: five services, nine roles.

## 2. Rules

* A client is identified by `client_id` + `client_secret`; there is no other way.
* The secret is **not stored in the clear** and cannot be read back — only rotated.
* A client's roles land in the token's `realm_access.roles` claim; a client without roles still
  gets a token — it simply will not be admitted anywhere.
* `azp` in the token equals `client_id`. The receiving side builds its check on the pair
  "`azp` + the required role" — which is how the existing validators were already written, so they
  did not have to change.
* A token lives 5 minutes (`expires_in: 300`). A client refreshes it 60 seconds before expiry — the
  interval has to stay comfortably above that lead time.
* No `refresh_token` is issued for `client_credentials`.
* The answer to a wrong secret and the answer to a non-existent client are
  **indistinguishable** — otherwise the list of clients can be enumerated.

## 3. How it works

```
service A ── POST /realms/{realm}/protocol/openid-connect/token ──▶ shildik
             grant_type=client_credentials, client_id, client_secret
             ◀── { access_token, expires_in, token_type }

service A ── a request with Authorization: Bearer <token> ──▶ service B
                                                               │
             service B ── GET .../certs (once a day, cached) ──┘
             verifies the signature, reads azp + realm_access.roles
```

The key part: **service B knows nothing about shildik**. It fetches JWKS from a URL in its
configuration. Replacing Keycloak with shildik is, for it, two values in a config file.

## 4. Code anchors

| What | Where |
|---|---|
| Issuing a token | `core/src/commonMain/.../core/feature/token/IssueServiceTokenUseCase.kt` |
| The route | `server/src/commonMain/.../server/oidc/OidcRoutes.kt` |
| JWS signing | `crypto/src/commonMain/.../crypto/Jws.kt` |
| Secrets: generation, hashing, comparison | `crypto/src/commonMain/.../crypto/Secrets.kt` |
| The consumer side | `oidc-auth-client` (asking for a token), `oidc-auth-server` (verifying it) |

## 5. Scenarios

### Scenario: a client gets a token carrying its roles
* **Given:** a client `orders-api` with the roles `orders:read` and `orders:write`.
* **When:** a token request with the right secret.
* **Then:** `200`; the token carries `azp: "orders-api"` and both roles in `realm_access.roles`.

### Scenario: the token is accepted by the consumer's validator *(the main one)*
* **Given:** `configureAuth` pointed at shildik's JWKS.
* **When:** a request carrying a shildik token arrives at a protected route.
* **Then:** it is accepted, and the principal carries `azp` and the roles.
* The check is done by **somebody else's code** — the one that runs in production. Until this
  scenario is green the milestone is not closed, however many of our own tests pass.

### Scenario: another client does not pass a role check
* **Given:** a client without the required role.
* **When:** a request to a route that demands it.
* **Then:** `403` — the rule on the receiving side finds no match.

### Scenario: an expired token is refused
* **Given:** a token older than `expires_in`.
* **Then:** `401`.

### Scenario: enumerating clients yields nothing
* **When:** a request with a wrong secret, and a request with a non-existent `client_id`.
* **Then:** the answers are byte-for-byte identical.
