# shildik-public

[![ktlint](https://img.shields.io/badge/ktlint%20code--style-%E2%9D%A4-FF4081.svg)](https://ktlint.github.io/)
[![kotlin](https://img.shields.io/badge/Kotlin-2.4.10-blue?logo=kotlin&logoColor=white)](https://kotlinlang.org)
[![ktor](https://img.shields.io/badge/Ktor-3.5.2-087CFA?logo=ktor&logoColor=white)](https://ktor.io)
[![native](https://img.shields.io/badge/Native-blue?logoColor=white)](https://kotlinlang.org)
[![jvm](https://img.shields.io/badge/JVM-orange?logoColor=white)](https://kotlinlang.org)
[![licence](https://img.shields.io/badge/licence-MIT-green.svg)](LICENSE)

The parts of [shildik](https://github.com/youndie) — an identity provider written in Kotlin — that
are useful without shildik itself.

Two libraries so far. Both exist because the JVM answer to the same problem does not compile for
Kotlin/Native, and a service that moves to a native binary should not have to give up token
verification or route-level authorization to get there.

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

## Targets

`jvm`, `linuxX64`, `linuxArm64`, `macosArm64` for `crypto`; `ktor-role-based-auth` is JVM, because
Ktor's authentication plugin is.

## Add it

```kotlin
repositories {
    maven("https://reposilite.kotlin.website/snapshots")
}

dependencies {
    implementation("ru.workinprogress.shildik:crypto:$version")
    implementation("ru.workinprogress.shildik:ktor-role-based-auth:$version")
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
