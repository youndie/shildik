# shildik-public

[![ktlint](https://img.shields.io/badge/ktlint%20code--style-%E2%9D%A4-FF4081.svg)](https://ktlint.github.io/)
[![kotlin](https://img.shields.io/badge/Kotlin-2.4.10-blue?logo=kotlin&logoColor=white)](https://kotlinlang.org)
[![ktor](https://img.shields.io/badge/Ktor-3.5.2-087CFA?logo=ktor&logoColor=white)](https://ktor.io)
[![native](https://img.shields.io/badge/Native-blue?logoColor=white)](https://kotlinlang.org)
[![jvm](https://img.shields.io/badge/JVM-orange?logoColor=white)](https://kotlinlang.org)
[![licence](https://img.shields.io/badge/licence-MIT-green.svg)](LICENSE)

The parts of [shildik](https://github.com/youndie) — an identity provider written in Kotlin — that
are useful without shildik itself.

Six modules so far. They exist because the JVM answer to the same problems does not compile for
Kotlin/Native, and a service that moves to a native binary should not have to give up token
verification, service tokens or route-level authorization to get there.

## `crypto` — JOSE without a JOSE library

RS256 signing and verification, JWK and JWKS, PKCE, PBKDF2 password hashing, AES-GCM encryption of
keys at rest — in `commonMain`, on top of
[cryptography-kotlin](https://github.com/whyoleg/cryptography-kotlin).

```kotlin
val key = SigningKey.generate(kid = "k1")
val token = Jws.sign(key, buildJsonObject { put("sub", "alice") })

val parsed = Jws.parse(token)!!
val verifier = VerificationKey.fromJwks(jwksJson).first { it.kid == parsed.kid }
val ok = verifier.verify(parsed.signingInput, parsed.signature)
```

What it deliberately does **not** do: pick an algorithm from the token header. There is one
algorithm and it is hard-wired — reading `alg` to decide how to verify is the road to `alg: none`.
`Jws.parse` returns `alg` only so a caller can reject an unexpected one.

## `ktor-role-based-auth` — roles on a route

```kotlin
routing {
    authenticate("jwt") {
        withRole("orders:read") { get("/orders") { /* … */ } }
        withAnyRole("billing:read", "billing:admin") { get("/invoices") { /* … */ } }
        withoutRoles("suspended") { post("/orders") { /* … */ } }
    }
}
```

The plugin asks a principal for its roles and nothing else — implement `RoleBasedPrincipal` on
whatever your authentication provider produces. It answers 403 when the roles do not match, and
stays out of the way when there is no principal at all: that case is 401, and it belongs to
authentication.

Grown from [omkar-tenkale/ktor-role-based-auth](https://github.com/omkar-tenkale/ktor-role-based-auth)
(Unlicense), rewritten for Ktor 3, with the JWT dependency dropped and the optional-authentication
behaviour pinned by a test.

## `oidc-auth-*` — talking to the provider

Three small modules that a Ktor service uses to live with an OIDC provider. All multiplatform, so
a service on Kotlin/Native keeps the same code.

Verifying tokens:

```kotlin
configureAuth(oidcConfig) { (roles, _, azp) ->
    azp == "orders-api" && "orders:read" in roles
}

routing {
    authenticate(JWT_AUTH_OIDC) {
        get("/orders") { call.principal<OidcPrincipal>() }
    }
}
```

The validator checks the signature and the expiry and fetches JWKS itself with a day-long cache.
`iss` is deliberately **not** checked: during a provider migration a service has to accept tokens
from both, and two key sources live side by side until the old one is gone.

Getting a service token, and a client that carries it:

```kotlin
val auth = OidcAuthService(oidcConfig)
val neighbour = provideClient(auth, oidcConfig, endpoint = "orders-api:8080/internal")
```

The token is cached and refreshed a minute before it expires; a 401 from the neighbour triggers a
refresh and one retry.

| Module | What it is |
|---|---|
| `shared-oidc` | the wire contract: addresses as Ktor resources, response models |
| `oidc-auth-core` | connection settings — `OidcConfig` |
| `oidc-auth-client` | a service token via `client_credentials`, plus `provideClient` |
| `oidc-auth-server` | the token validator for a Ktor service |

The address shape follows Keycloak (`/realms/{realm}/protocol/openid-connect/…`). That is
deliberate rather than accidental: it let services and libraries already speaking to one provider
be pointed at another without touching their configuration.

## Targets

`jvm`, `linuxX64`, `linuxArm64` and `macosArm64` for everything except `ktor-role-based-auth`,
which is JVM, because Ktor's authentication plugin is.

## Add it

```kotlin
repositories {
    maven("https://reposilite.kotlin.website/snapshots")
}

dependencies {
    implementation("ru.workinprogress.shildik:crypto:$version")
    implementation("ru.workinprogress.shildik:ktor-role-based-auth:$version")
    implementation("ru.workinprogress.shildik:oidc-auth-client:$version")
    implementation("ru.workinprogress.shildik:oidc-auth-server:$version")
}
```

## Development

```bash
./gradlew check     # build, ktlint, tests on JVM and on a native target
```

## Status

Early. These modules run in production inside a private identity provider, but their API here is
new and may still move. The provider itself is not open source — what is published is what stands
on its own.
