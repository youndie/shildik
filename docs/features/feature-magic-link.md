---
id: feature-magic-link
title: The magic link as a sign-in method
type: feature
status: active
tags: [identity, auth, passwordless, m4]
---

# The magic link

The second sign-in method after Google — and the first one the project was started for: in
Keycloak it lives as a class inside somebody else's process (a hand-built `magic-link-spi.jar`
whose sources were lost).

## 1. What is replaced and what is not

The email, its template, the codes and the rate limiting stay in **the calling service**: that is
product work, it already exists, and it has nothing to do with identity. One link in the chain
changes — the one Keycloak used to be.

```
caller       email with a link → code → handoff JWT (HMAC256, claim email, exp +30 s)
front end    signIn("keycloak", …, { handoff_token })   ← puts the token into the /auth query
provider     verifies the handoff JWT and starts a session   ← this is our part
```

The contract was taken **from the code** rather than from a description. What matters:

| What | Value | Why it matters |
|---|---|---|
| Algorithm | `HS256`, a shared secret | Not our RS256: two parties know the secret, and that is deliberate — the caller **asserts** an identity rather than reporting a fact |
| Claims | `email`, `iat`, `exp` | No `iss`, no `aud`, no `jti` |
| Lifetime | 30 seconds | Enough for a redirect, not enough to forward the email |
| Transport | the `handoff_token` parameter of `/auth` | Travels in the address bar — see §4 |

## 2. Whom it recognises

Here is the main trap, and it has already cost one investigation.

Every imported person carries a `google` identity with their Google `sub`. **Nobody** carries a
`magic` identity: in Keycloak the magic-link accounts were created without a federated link, so
there was nothing to import. A lookup by the pair (method, identifier) therefore finds nobody, and
a sign-in by link would create a **second** user whose `sub` looks like `owner@example.com`. To a
relying service that is a different person: an empty account behind a working sign-in.

**The answer: linking by a verified email.** When the lookup by identity finds nothing, we look by
`email` — and, having found a record, attach the new identity to it without touching its `id`.

This is safe exactly as far as the sign-in method **proves** ownership of the email. The magic link
does prove it: the letter went to that address and the code came back from it.

The proof is a property of **this sign-in**, not of the method
([`AuthenticatedSubject.emailVerified`](../../core/src/commonMain/kotlin/ru/workinprogress/shildik/core/feature/auth/AuthMethod.kt)).
It used to be a property of the method, and that was wrong: Google does not always verify an email
— it has an `email_verified` field and it is sometimes `false`. A property of the method would
answer "yes" for those answers too.

The reverse — "found by email, but the method does not prove it" — is textbook account takeover:
register with somebody else's address, sign in to their account.

## 3. What this method does not do

* **It does not create an account in Keycloak.** That Admin API call was the last thing keeping
  Keycloak in the cluster.
* **It does not send email.** The caller's mail is already configured; adding a second sender so
  that "everything is in the provider" replaces one dependency with two.
* **It does not store codes.** Single use and expiry belong to the caller, and are covered by its
  own tests.

## 4. Known weaknesses, accepted knowingly

* **The token travels in the query.** It will land in proxy logs and in browser history. The
  mitigation is the same 30 seconds plus the single use of the original code. Changing the
  transport means changing the front end and the order of the switch-over as well; as a separate
  task — yes, as part of this one — no.
* **A shared secret.** Compromising the caller's secret means signing in as any address. Rotation
  is mandatory and is done in both places at once.

## 5. Acceptance

* **Scenario:** a handoff JWT built **exactly the way the caller builds it** (HMAC256, claim
  `email`, `exp` +30 s) is presented to `/auth?auth_method=magic&handoff_token=…`.
* **Then:** a code is issued, exchanging it yields an `id_token` whose `sub` equals the identifier
  of the person who **already exists** with that email — not the email itself.
* **And:** an expired token, a signature with another secret and a tampered `email` claim grant no
  sign-in.
