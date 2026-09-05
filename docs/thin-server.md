---
id: thin-server
title: Assembling a server of your own
type: guide
status: active
---

# A server of your own

The reference distribution in [`:distribution`](../distribution) carries every sign-in method this
repository has, and turns each on when its configuration appears. That is the right shape for
trying the provider and the wrong shape for running one: **a method that is present can be
switched on, and a method that is absent cannot.**

So the recipe below is not a customisation of the reference build. It is the normal way to run
this: two files, and a dependency list that *is* the feature set.

## The whole thing

`Main.kt`:

```kotlin
fun main() =
    runShildik(
        storage = { config ->
            sqlx4kStorageModule(config.jdbcUrl, config.dbUser, config.dbPassword)
        },
    ) {
        listOf(PasswordAuthMethod(get(), get(), get(), get()))
    }
```

`build.gradle.kts`:

```kotlin
kotlin {
    linuxX64 {
        binaries.executable {
            entryPoint = "com.example.idp.main"
            baseName = "idp"
        }
    }
    sourceSets.linuxX64Main.dependencies {
        implementation("io.github.youndie.shildik:server-boot:$shildik")
        implementation("io.github.youndie.shildik:storage-sqlx4k:$shildik")
        implementation("io.github.youndie.shildik:auth-password:$shildik")
        implementation("io.ktor:ktor-server-cio:$ktor")
    }
}
```

That is a complete identity provider: OIDC on the public port, the admin API on the management
port, one sign-in method, PostgreSQL behind it.

**There is no `auth-google` in that list, so there is no Google sign-in.** Not disabled — absent.
No environment variable, no `values.yaml` and no mistake in a Helm template can bring it back,
because the code is not in the binary. That is the property worth having, and it is the reason
this file exists separately from the README.

## The same thing on SQLite

Two lines of the recipe change, and nothing else does:

```kotlin
fun main() =
    runShildik(
        storage = { config -> sqlx4kSqliteStorageModule(config, optional("SHILDIK_MIGRATIONS")) },
    ) {
        listOf(PasswordAuthMethod(get(), get(), get(), get()))
    }
```

```kotlin
implementation("io.github.youndie.shildik:storage-sqlx4k-sqlite:$shildik")
```

The ports, the repositories and the SQL are the same ones — `storage-sqlx4k-sqlite` supplies a
driver and a schema written in SQLite's types, and takes everything else from
`storage-sqlx4k-core`. Do not depend on both storage modules at once: each driver links its own
Rust runtime, and a native binary that reaches two of them fails at the linker on duplicate
symbols.
The file is created on first start, directory included, and `SHILDIK_MIGRATIONS` points at the
`migrations` directory of **this** module: the two schemas are not interchangeable, and a build
that unpacks the Postgres one will fail on the first statement rather than quietly disagree.

**What you give up is a second replica.** One SQLite file cannot be shared by two pods — not as a
performance limit but as data loss — so a SQLite installation runs a single instance and takes its
downtime on every deploy. That is the trade an installation makes: no database to operate, and no
rolling restart. For anything with more than one pod, Postgres is next door and the swap is the
one line above.

## What `runShildik` does for you

Reads the configuration from the environment, builds the object graph, starts two Ktor engines
and blocks. The variables it reads:

| Variable | |
|---|---|
| `SHILDIK_ISSUER` | required; must equal the address the service actually answers on — clients read it from discovery and compare |
| `SHILDIK_MASTER_KEYS` | required; comma-separated, the first encrypts and the rest are accepted while a rotation is in progress |
| `SHILDIK_DB_USER`, `SHILDIK_DB_PASSWORD` | required — unless `SHILDIK_DB_PATH` is set, because a file has no account |
| `SHILDIK_JDBC_URL` | `jdbc:postgresql://localhost:5432/shildik` by default |
| `SHILDIK_DB_PATH` | a SQLite file; setting it is what makes a build on `storage-sqlx4k-sqlite` a SQLite installation |
| `SHILDIK_PORT`, `SHILDIK_MANAGEMENT_PORT` | `8080` and `9000` |
| `SHILDIK_BOOTSTRAP_TOKEN` | optional; without it a random one is printed to the log while no admin client exists |
| `SHILDIK_MIGRATIONS` | a directory; absent means migrations do not run at all |

**Secrets have no defaults on purpose.** A missing one brings start-up down instead of surfacing
on the first request — a default for a secret is a way to reach production with a default secret.

## Where the seams are

Three parameters, and each exists because the alternative was worse:

**`storage`** is a function rather than a default. It used to be wired in through an ORM, and that
made the shared start-up JVM-only: JDBC is a JVM interface, not a protocol. A default here would
drag the same dependency back in.

**`authMethods`** is a lambda evaluated **inside** the container, because a method may need
repositories — the password one does — and they live there. `get()` inside it is Koin's.

**`observability`** is an `Application.() -> Unit`, empty by default. Attach a metrics agent, a
tracer, nothing. It is a parameter rather than something clever inside, because a provider that
refuses to start over unreachable telemetry has taken sign-in down for the sake of a graph:

```kotlin
runShildik(
    storage = { … },
    observability = { install(MetricsAgent) { endpoint = optional("METRIK_ENDPOINT") ?: return@install } },
    reporter = { error -> crashTracker.report(error) },
) { … }
```

`reporter` is where unhandled failures of the OIDC surface go. Its default is the log rather than
silence — "nothing arrives in monitoring" must not be indistinguishable from "nothing is
breaking".

## The image

The Dockerfile in [`docker/native.Dockerfile`](../docker/native.Dockerfile) takes a binary and a
`migrations` directory and produces a distroless image with two ports. It is a recipe, not a
framework: copy it, or copy the eight lines of it you need.

```bash
./gradlew :distribution:image     # in this repository: context + docker build
```

The reference image is 44 MB. Run it against a Postgres and it answers discovery, issues a
service token and serves JWKS — checked, under qemu on an ARM laptop, with the container holding
54 MiB and Ktor reporting `Application started in 0.027 seconds`:

```bash
docker network create idp
docker run -d --name pg --network idp \
  -e POSTGRES_USER=shildik -e POSTGRES_PASSWORD=secret -e POSTGRES_DB=shildik postgres:18-alpine

docker run -d --name idp --network idp -p 8080:8080 -p 9000:9000 \
  -e SHILDIK_ISSUER=http://127.0.0.1:8080 \
  -e SHILDIK_MASTER_KEYS=change-me \
  -e SHILDIK_JDBC_URL=jdbc:postgresql://pg:5432/shildik \
  -e SHILDIK_DB_USER=shildik -e SHILDIK_DB_PASSWORD=secret \
  -e SHILDIK_BOOTSTRAP_TOKEN=bootstrap \
  shildik:0.2.0

curl -X POST localhost:9000/admin/tenants -H 'Authorization: Bearer bootstrap' \
  -H 'Content-Type: application/json' -d '{"realm":"main"}'
```

The second port is published here only because this is a laptop. In a cluster it is not.

On SQLite there is nothing to start beside it, and `:distribution-sqlite` is the same reference
build against `storage-sqlx4k-sqlite`:

```bash
./gradlew :distribution-sqlite:image

docker run -d --name idp -p 8080:8080 -p 9000:9000 -v shildik:/data \
  -e SHILDIK_ISSUER=http://127.0.0.1:8080 \
  -e SHILDIK_MASTER_KEYS=change-me \
  -e SHILDIK_DB_PATH=/data/shildik.db \
  -e SHILDIK_BOOTSTRAP_TOKEN=bootstrap \
  shildik-sqlite:0.2.0
```

That image is 46 MB, starts in 0.035 s, and answers discovery, issues a service token and serves
JWKS — checked under qemu on an ARM laptop, restart included: the tenant, the client and the
signing key were all still there afterwards.

**Backing that volume up is not `cp` of one file.** The driver runs SQLite in WAL mode, so the
database is three files — `shildik.db`, `-wal` and `-shm` — and the newest writes live in the WAL
until a checkpoint. A copy of the first one alone, taken from a running provider, is a database
missing whatever happened recently. Copy all three with the process stopped, or let SQLite make the
copy (`VACUUM INTO`).

For your own build the context is three things and nothing else — the Dockerfile, the binary, and
the schema. Take the schema out of the storage artifact rather than keeping a copy: it is inside
`storage-sqlx4k-jvm.jar` under `migrations/`, and a second copy of a schema drifts from the first
in silence.

## What this does not save you from

- **Running a database.** The provider is stateless; the state is Postgres — or the SQLite file,
  which is yours to back up just the same and is easier to forget precisely because nothing has to
  be operated. The signing keys live there, encrypted with `SHILDIK_MASTER_KEYS` — lose both and
  the tokens you issued are unverifiable.
- **Deciding who may sign in.** An external provider answers "who is this", never "this one is
  allowed". A tenant created with `--closed` admits only people an administrator provisioned.
- **Exposing the management port.** It has no business behind the same ingress as the public one.
  The 404 an outsider gets for `/admin` is a property of the two engines, not of a check you
  could add later.
