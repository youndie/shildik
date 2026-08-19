---
id: feature-signing-keys
title: Signing keys and their rotation
type: feature
status: active
api:
  - protocol-oidc-subset
  - endpoint-admin
tags: [identity, crypto, mvp]
---

# Signing keys and their rotation

## 1. In short

shildik signs tokens with a private key and serves the public half through JWKS. Clients fetch
JWKS and verify signatures themselves — the service takes no part in the verification and need not
know about it.

Rotation is not "good practice in general" but a requirement: a key that cannot be replaced cannot
be compromised safely either.

## 2. Rules

* The algorithm is **RS256**. One algorithm: the ability to choose here is the ability to choose
  `none`.
* A key has three states: `active` (we sign with it), `retiring` (we do not sign, but still serve
  it in JWKS), `retired` (nowhere).
* JWKS carries **every** `active` and `retiring` key.
* Rotation moves the current `active` to `retiring` and creates a new `active`. The previous key
  is **not deleted** — see §3.
* A key cannot move to `retired` sooner than **24 hours** after it became `retiring`.
* The private half never leaves the service: neither the admin API, nor the CLI's `export`, nor
  the log contains it.
* In storage the private half is **encrypted** with a master key from `SHILDIK_MASTER_KEYS`.
  Rotation stays an operation of the service itself: generating a pair needs neither an external
  actor nor access to cluster secrets.
* The JWS header carries `kid`; without it a client cannot pick a key out of the set.

## 3. Where the 24 hours come from

Not caution, but a measurement: a common Ktor validator configures its JWKS cache as
`.cached(10, 24, TimeUnit.HOURS)`.

A client that fetched JWKS will not come back for a day. Remove the old key right after a rotation
and you get: a fresh token signed with the new key, a client whose cache holds only the old one →
`401` on everything, without a single error in shildik's log. The failure looks like a problem on
the client's side and takes a long time to find.

Hence the `retiring` state: it exists **only** for somebody else's cache.

## 4. Code anchors

| What | Where |
|---|---|
| The active key, lazy creation, cache | `core/src/commonMain/.../core/feature/keys/ActiveSigningKey.kt` |
| JWKS | `core/src/commonMain/.../core/feature/keys/GetJwksUseCase.kt` |
| Rotation and the ban on early retirement | `core/src/commonMain/.../core/feature/admin/AdminUseCases.kt` |
| Key generation, JWK, signing | `crypto/src/commonMain/.../crypto/SigningKey.kt` |
| Encryption of the private half | `crypto/src/commonMain/.../crypto/MasterKeyCipher.kt` |
| RSA and the JWK format | `dev.whyoleg.cryptography:cryptography-core` 0.6.0, `RSA.PKCS1`, `RSA.PublicKey.Format.JWK` |

## 5. Scenarios

### Scenario: JWKS carries both the active and the retiring key
* **Given:** `K1` is `retiring`, `K2` is `active`.
* **When:** `GET .../certs`.
* **Then:** both keys are in the answer, each with its own `kid`.

### Scenario: tokens issued before a rotation keep working
* **Given:** a token signed with `K1`, not yet expired.
* **When:** a rotation happens.
* **Then:** the token still verifies.

### Scenario: retiring a key early is refused
* **Given:** `K1` became `retiring` an hour ago.
* **When:** an attempt to move it to `retired`.
* **Then:** a refusal that says when it will become possible.
* The rule exists for somebody else's cache, and at that moment a person is usually certain that
  they, at least, know what they are doing.

### Scenario: the private half does not leak
* **When:** `GET /admin/tenants/{t}/keys` and `shildik export`.
* **Then:** the output holds public JWKs only; no field carries the private exponent.
