---
id: protocol-oidc-browser
title: The browser contour of OIDC
type: api
status: active
tags: [identity, oidc, m4]
---

# The browser contour

The service-to-service contour ([protocol-oidc-subset](protocol-oidc-subset.md)) is
`client_credentials` and nothing else. Here comes everything it did not have: redirects, codes,
PKCE, `id_token`, refreshing.

The requirements were taken **from a consumer's code** rather than from the specification: the
specification permits a great deal, and what has to work is what a real front end does.

## 1. Who the consumer is and what it does

```ts
Keycloak({
  clientId: "web-app",
  issuer: `${SERVER}/realms/${REALM}`,
  authorization: { params: { scope: "openid profile email offline_access" } },
})
```

The contract follows, and every line of it is mandatory:

| What | Why exactly this |
|---|---|
| **A public client** | There is no secret in the configuration. So the front end is a public client, and the protection rests on PKCE rather than on a secret |
| **Discovery at `{issuer}/.well-known/openid-configuration`** | `oauth4webapi` reads it itself and compares the `issuer` field **strictly** against the address it read it from |
| **`id_token`** | The provider is declared as OIDC. Without an `id_token` next-auth creates no session, however many access tokens are issued |
| **`refresh_token`** | `offline_access` was requested, and the refresh call sends `grant_type=refresh_token` with a `client_id` and **no secret** |
| **PKCE `S256`** | For a public client `oauth4webapi` turns it on itself and requires it to be advertised in discovery |

## 2. What discovery has to carry

```
authorization_endpoint          .../protocol/openid-connect/auth
token_endpoint                  .../protocol/openid-connect/token
jwks_uri                        .../protocol/openid-connect/certs
userinfo_endpoint               .../protocol/openid-connect/userinfo
end_session_endpoint            .../protocol/openid-connect/logout
response_types_supported        ["code"]
grant_types_supported           ["authorization_code","client_credentials","refresh_token"]
code_challenge_methods_supported ["S256"]
scopes_supported                ["openid","profile","email","offline_access"]
subject_types_supported         ["public"]
id_token_signing_alg_values_supported ["RS256"]
```

Addresses of shildik's own (`/realms/{realm}/oauth2/…`) are served alongside the inherited ones, and
discovery advertises **the new ones**: a client reads them from here, which is the only mechanism by
which the move happens without edits at the consumer. The inherited routes keep answering for
anything that hardcoded them.

## 3. The client model

For the browser a client needs more than `clientId`, a secret hash and roles:

* **`public: Boolean`** — a public client has no secret at all. Not an "empty secret" but none: an
  empty secret is a secret that fits anybody;
* **`redirectUris: Set<String>`** — where the code may be returned. Matched **exactly**, with no
  wildcards: a `*` in a redirect_uri has historically been the main way to steal another client's
  code.

Hence a rule worth writing down before implementing: **a public client cannot get a token through
`client_credentials`.** Otherwise a front end whose "secret" everybody knows receives a service
token.

## 4. The authorization code

It lives for minutes and is single-use. It is stored next to everything else (Postgres) rather than
in memory: a pod restarts between the redirect and the exchange, and a lost code looks like
"sign-in works every other time".

What is stored: the code itself (hashed, like a client secret), `clientId`, `redirectUri`, `userId`,
`codeChallenge`, `scope`, `nonce`, and an expiry. The exchange is atomic: the code is marked used in
the same transaction that issues the token, otherwise presenting it twice yields two tokens.

## 4a. The refresh token: rotation and replay detection

The front end asks for `offline_access` and refreshes through `grant_type=refresh_token` **without a
secret** — a public client has nowhere to get one.

Two decisions follow:

1. **Rotation.** Every exchange spends the presented token and issues a new one. Without it a leaked
   refresh token would work for a month — exactly as long as it lives.
2. **Replay detection.** Presenting an already spent token means somebody saved it and is using it
   alongside the rightful owner — but **only if more than a few seconds have passed**. Inside that
   window it is the normal behaviour of a client whose page is rendered by several server components
   at once, and revoking the chain for that logs the owner out for nothing. Outside it there is no
   honest explanation, so **the whole chain** grown from one sign-in is revoked, not just the
   presented token. A user will survive signing in again; continuing to issue tokens to whoever
   presented something stolen is not survivable.

A refresh token is issued **only when `offline_access` was asked for**: a long-lived secret for
somebody who did not ask is risk without benefit.

## 5. Acceptance

The main scenario is the same in spirit as for the service contour: it exercises **somebody else's
code**, not ours.

* **Scenario:** `oauth4webapi` (the one inside next-auth) reads shildik's discovery, walks
  authorization code + PKCE and receives a pair of tokens.
* **Then:** the `id_token` verifies with its own tools, `sub` matches the identifier carried over
  from Keycloak ([feature-user-import](../features/feature-user-import.md)), and the relying service
  recognises the person as the owner they were.

Until this scenario is green the milestone is not closed, however many of our own tests pass.
