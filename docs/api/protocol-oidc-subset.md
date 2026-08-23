---
id: protocol-oidc-subset
title: The subset of OIDC shildik serves
type: protocol
status: active
---

# The subset of OIDC

The shape of the URLs repeats Keycloak's deliberately: that is what lets services already speaking
to one provider be pointed at another by editing a config value. Addresses of our own exist
alongside them — see [protocol-oidc-browser](protocol-oidc-browser.md).

## 1. What is served and why exactly this

| Endpoint | What for | Who consumes it |
|---|---|---|
| `POST /realms/{realm}/protocol/openid-connect/token` | issuing a service token | every service that calls a neighbour |
| `GET /realms/{realm}/protocol/openid-connect/certs` | JWKS for verifying signatures | every service that receives a token |
| `GET /realms/{realm}/.well-known/openid-configuration` | discovery | oauth2-proxy, next-auth and anything that reads a provider's addresses |

The browser half of OIDC — authorization code, PKCE, `userinfo`, `end_session` — arrived in a later
milestone and is documented separately. Introspection and revocation are not served at all.

## 2. `POST .../token` — the `client_credentials` grant

Client authentication is `client_id` + `client_secret`, accepted **both in the form body and in an
`Authorization: Basic` header**: RFC 6749 §2.3.1 allows both, and the client picks, not us.

The request is `application/x-www-form-urlencoded`:

```
grant_type=client_credentials&client_id=<id>&client_secret=<secret>
```

The answer is `200`, `application/json`:

```json
{ "access_token": "<JWS>", "expires_in": 300, "token_type": "Bearer" }
```

No `refresh_token` is issued for `client_credentials`.

Errors follow [RFC 6749 §5.2](https://www.rfc-editor.org/rfc/rfc6749#section-5.2): `400` with
`{"error": "invalid_client"}`. "No such client" and "wrong secret" must not be distinguishable in
the answer — that is client enumeration.

### Token claims

The shape is dictated by validators that already existed:

| Claim | Value | Who reads it |
|---|---|---|
| `iss` | `{issuer}/realms/{realm}` — always the address discovery is served at | relying services, as part of a person's identity; next-auth and oauth2-proxy compare it with the issuer from discovery |
| `sub` | the client's id (for a service token) | relying services |
| `azp` | `client_id` | everyone: the first level of "who came" |
| `realm_access.roles` | an array of strings | everyone: the second level |
| `email` | user tokens only; absent from service tokens | services that tell a person from a program |
| `aud` | the resources this token is addressed to; **absent** when the client has none | a resource server deciding whether the token was meant for it |
| `exp`, `iat`, `jti` | standard | — |

### `resource` — what a token is addressed to

A token without `aud` is a token every service may be shown. That was every token this provider
issued, and it is survivable exactly as long as all the services belong to the same people: the
moment one of them is something a program talks to on somebody's behalf, a token taken from that
program opens a person's screens as well, and neither service can tell it was not meant for them.

So `resource` ([RFC 8707](https://www.rfc-editor.org/rfc/rfc8707)) is accepted at the token
endpoint, may be repeated, and applies to all three grants:

* a client may only name a resource it was **granted** — otherwise asking for an audience would be a
  way to mint a token for a service the client has no business with, the reverse of what the claim
  is for. A resource not on its list is refused with `invalid_target`, deliberately not
  `invalid_client`: the client is who it says it is, and whoever configured it needs to see that the
  list is missing an entry rather than that the secret is wrong;
* naming **nothing** yields everything the client is entitled to;
* a client entitled to nothing gets a token with no `aud` — which is what keeps every client
  configured before this existed working unchanged.

One resource is written as a string, several as an array. A single-element array is legal too and is
not used: it is the form readers most often get wrong, and there is nothing to gain by finding out
which ones.

Which resources a client may name is part of the client ([feature-client-admin](../features/feature-client-admin.md)).

The nesting of `realm_access.roles` is not a whim but what an existing validator parses out of
`payload.getClaim("realm_access").asMap()["roles"]`.

The signature is **RS256**. The JWS header carries `kid`; a client picks the key out of JWKS by it.

## 3. `GET .../certs` — JWKS

`200`: `{"keys": [ <JWK>, … ]}`, each key the public half in JWK form with `kid`, `kty: RSA`,
`alg: RS256`, `use: sig`.

**Every key in the `active` and `retiring` states is served, not only the current one.** The reason
is a day-long cache on the consumer side — see
[feature-signing-keys](../features/feature-signing-keys.md).

Response caching: `Cache-Control: max-age=300`. Shorter than the client's cache on purpose: if a key
ever has to be revoked in a hurry, the window is set by the client anyway, and shortening it on our
side achieves nothing.

## 4. `GET .../.well-known/openid-configuration`

`issuer`, `token_endpoint`, `authorization_endpoint`, `jwks_uri`, `userinfo_endpoint`,
`end_session_endpoint`, plus `grant_types_supported`,
`id_token_signing_alg_values_supported: ["RS256"]` and
`code_challenge_methods_supported: ["S256"]`.

**Only what is actually supported is advertised.** The temptation to copy Keycloak's full document
"just in case" leads straight to debugging an endpoint that does not exist: oauth2-proxy and
next-auth read discovery and believe it. `plain` is deliberately absent from the PKCE methods — it
offers no protection, and a client picks from what is advertised.

`userinfo_endpoint` must be there even if nothing calls it: `@auth/core` throws a `TypeError` on a
discovery document without it — found in its code, not in the specification.

**A realm that does not exist is refused here**, with `404` and `unknown_realm`, rather than
described. Every address in the document is built out of the issuer, so a document for a realm
nobody created is shaped exactly like a real one; since this is the first request any client makes,
answering it would turn a mistyped realm into a provider that looks healthy until `jwks` — the first
address here that asks storage anything — refuses. The refusal carries the same CORS headers as the
document: a page has to be able to read the reason.

## 5. Acceptance scenarios

### Scenario: a service client gets a token
* **Given:** a client `orders-api` with the role `orders:read`.
* **When:** `POST .../token` with `grant_type=client_credentials` and the right secret.
* **Then:** `200`; `access_token` carries `azp: "orders-api"`, `realm_access.roles` contains
  `orders:read`, and there is no `email`.

### Scenario: the token passes the consumer's validator
* **Given:** an issued token and a running `configureAuth` pointed at shildik's JWKS.
* **When:** a request carrying that token arrives at a protected route.
* **Then:** it is accepted; `azp` and the roles are visible in the principal.
* This is the **main** scenario: the check is done not by our code but by the code that runs in
  production.

### Scenario: a wrong secret is indistinguishable from a non-existent client
* **When:** a request with a valid `client_id` and a wrong secret; then one with a `client_id` that
  does not exist.
* **Then:** both answer `400 {"error": "invalid_client"}`, and the bodies are identical.

### Scenario: a retiring key stays in JWKS
* **Given:** a key moved to `retiring` less than 24 hours ago.
* **When:** `GET .../certs`.
* **Then:** the key is in the answer.

## 6. Code anchors

| What | Where |
|---|---|
| Routes and discovery | `server/src/commonMain/.../server/oidc/OidcRoutes.kt` |
| Issuing a token | `core/src/commonMain/.../core/feature/token/IssueServiceTokenUseCase.kt` |
| JWKS | `core/src/commonMain/.../core/feature/keys/GetJwksUseCase.kt` |
| The consumer side | `oidc-auth-client`, `oidc-auth-server` |
