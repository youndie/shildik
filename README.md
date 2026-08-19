# shildik

[![ktlint](https://img.shields.io/badge/ktlint%20code--style-%E2%9D%A4-FF4081.svg)](https://ktlint.github.io/)
[![kotlin](https://img.shields.io/badge/Kotlin-2.4.10-blue?logo=kotlin&logoColor=white)](https://kotlinlang.org)
[![ktor](https://img.shields.io/badge/Ktor-3.5.2-087CFA?logo=ktor&logoColor=white)](https://ktor.io)
[![native](https://img.shields.io/badge/Native-blue?logoColor=white)](https://kotlinlang.org)
[![jvm](https://img.shields.io/badge/JVM-orange?logoColor=white)](https://kotlinlang.org)
[![licence](https://img.shields.io/badge/licence-MIT-green.svg)](LICENSE)

shildik — an OpenID Connect provider written in Kotlin, multiplatform, running on the JVM and as a
Kotlin/Native binary — together with the libraries a service needs to live with it.

It exists because the way a sign-in method is added mattered more than the feature list. In Keycloak
a magic link lives as a jar built against somebody else's SPI, loaded into somebody else's process.
Here a sign-in method is a module implementing one interface, and a distribution is a `main()` that
lists the ones it wants: what a build does **not** carry is a fact of the build rather than a
setting.

What is here: the domain, its storage, the HTTP layer, three sign-in methods, an admin CLI, and the
client-side libraries. What is not: our own distribution, charts and runbooks — those describe an
installation, not a provider.

## The provider

| Module | What it is |
|---|---|
| `core` | the domain: tenants, clients, users, codes, tokens, signing keys |
| `server` | the HTTP layer — the OIDC contour, the admin API, the sign-in page |
| `storage-sqlx4k` | PostgreSQL behind the domain's ports, on the JVM and on native alike |
| `shared` | the admin API wire: addresses as Ktor resources, models |
| `auth-password` | sign-in by password: PBKDF2, attempt counting, lock-out |
| `auth-google` | sign-in through Google |
| `auth-magic-link` | sign-in by a link from an email, verified from a handoff token |
| `cli` | a native `shildik` binary for the management port |

Assembling a distribution is the consumer's `main()` — the recipe is
[docs/thin-server.md](docs/thin-server.md):

```kotlin
val server = shildikServer(
    config = ShildikConfig(issuer = "https://id.example.com", /* … */),
    storage = { config -> sqlx4kStorageModule(config.jdbcUrl, config.dbUser, config.dbPassword) },
)
server.start(wait = true)
```

Sign-in methods are handed to the graph the same way — by listing them. A build without
`auth-password` has no password sign-in, and no configuration can bring it back.

Two ports, always: the public contour serves tokens, JWKS, discovery and the sign-in page; the
management contour serves `/admin` and is never exposed. They are two Ktor engines rather than one
with a check on each route, so a request to `/admin` from outside gets a 404 — the existence of a
management contour is not confirmed.

## The libraries

They are useful without the provider, and predate it here: the JVM answer to the same problems does
not compile for Kotlin/Native, and a service that moves to a native binary should not have to give
up token verification, service tokens or route-level authorization to get there.

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

**Addresses are asked of the provider, not assumed.** The client reads `token_endpoint` and the
validator reads `jwks_uri` from the discovery document, remembering the answer. A provider with no
discovery falls back to the Keycloak-shaped path (`/realms/{realm}/protocol/openid-connect/…`) —
something without discovery is older, not newer.

That shape is also what `RealmResource` describes, and it is deliberate rather than accidental: it
let services and libraries already speaking to one provider be pointed at another without touching
their configuration. `OAuth2` describes the same surface under addresses without the inheritance;
which of them a given provider serves is its business, and the libraries no longer need to know.

## Targets

`jvm`, `linuxX64`, `linuxArm64` and `macosArm64`, with two exceptions: `ktor-role-based-auth` is
JVM, because Ktor's authentication plugin is; `storage-sqlx4k` is JVM and `linuxX64`, which is what
the driver publishes.

## Add it

```kotlin
repositories {
    maven("https://reposilite.kotlin.website/snapshots")
}

dependencies {
    // The provider
    implementation("ru.workinprogress.shildik:server:$version")
    implementation("ru.workinprogress.shildik:storage-sqlx4k:$version")
    implementation("ru.workinprogress.shildik:auth-password:$version")

    // The libraries a consuming service needs
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

Early in the open, not early in life. This code runs in production: it replaced Keycloak for a
platform whose owners sign in through it every day, and it has been through a migration of users, a
move to Kotlin/Native and a PostgreSQL major upgrade. What is new is the packaging — the module
boundaries and the published API may still move.

The documentation of the provider — the wire contract, the behaviour and the CLI — is in
[docs/](docs/README.md). Deployment runbooks and the post-mortem of our own migration are not:
they describe an installation rather than a provider.
