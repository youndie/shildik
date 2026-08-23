---
id: cli
title: shildik — a CLI instead of an admin console
type: service
status: active
module: ":cli"
tech_stack: [Kotlin/Native, Clikt, Mordant, Ktor Client]
targets: [macosArm64, linuxX64, linuxArm64]
---

# The CLI

## 1. What it is for

The only management interface: tenants, clients, roles, users, keys. It replaces an admin console —
not as a placeholder until a UI appears, but as the main tool. An IdP's configuration changes
rarely, reproducibility matters more than convenience, and a command in a deployment script is more
reliable than fifteen clicks nobody wrote down.

Clicks are exactly how the Keycloak configuration this replaced had been created, and reproducing it
turned out to be impossible. The CLI is the direct answer to that.

**What it does not do:** check business rules. `clientId` uniqueness, whether a role is allowed, the
order of key rotation — those belong to the server's domain. A CLI that validates on its own is a
second implementation of the rules, drifting away from the first.

## 2. The contract

A client of [endpoint-admin](api/endpoint-admin.md). The management port is not exposed, so the CLI
runs where that port is reachable: a `kubectl port-forward`, a pod in the same namespace, or a CI
step.

## 3. Commands

```
shildik tenant list
shildik tenant create <name> [--closed]

shildik client list            [--tenant <t>]
shildik client create <id>     [--tenant <t>] [--role <r>]... [--public] [--redirect-uri <u>]... [--audience <r>]...
shildik client rotate-secret <id>
shildik client import-secret <id>          # the secret is read from stdin
shildik client set-roles <id> --role <r>...
shildik client audiences <id> --audience <r>...   # which resources its tokens may be addressed to

shildik client delete <id>

shildik user list              [--tenant <t>]
shildik user import --from-keycloak <url>  # credentials come from the environment
shildik user set-password <id>             # the password is read from stdin

shildik key list               [--tenant <t>]
shildik key rotate             [--tenant <t>]
shildik key retire <kid>
shildik key reencrypt                      # after a master-key change

shildik export                 [--tenant <t>]
shildik plan   -f <file>                   # what apply would do
shildik apply  -f <file>
```

`export`/`apply` are not decoration: they are what the predecessor lacked. A configuration has to
travel to git and land on an empty instance with one command; that is what could not be done, and
why a contour could not be raised again. `export` **carries no secrets** — they cannot be read back
by design, and placeholders stand in their place.

Secrets and passwords are read from **stdin** rather than from arguments: an argument is visible in
`ps` and settles in the shell history.

## 3a. Why native

The CLI is a native binary rather than a JVM application. It has neither the crypto nor the ORM that
kept the server on the JVM for a while, and it has a reason of its own — **start-up**. The tool is
called from scripts and CI steps, where hundreds of milliseconds of JVM start-up cost more than
everything else it does.

Output goes through Mordant (which arrives as a dependency of Clikt): tables, colour, progress. The
rendering lives behind a thin interface, so commands do not know what prints their result.

## 4. Output rules

* **A secret is printed once** — at `create` and at `rotate-secret`. That is the only place in the
  whole system where it is visible.
* Machine-readable output (`--output json`) is mandatory from the first version: a CLI lands in
  scripts before it lands in hands.
* A non-zero exit code on any error — otherwise a CI step named "create the client" stays green
  while no client was created.

## 5. Quirks

* **Options come after the subcommand.** `shildik client list --output json`, not
  `shildik --output json client list`: `--url`, `--token` and `--output` are declared on the API
  command rather than on the root. All of them are also read from the environment (`SHILDIK_URL`,
  `SHILDIK_TOKEN`, `SHILDIK_OUTPUT`), which is easier in a script than flags.
* **`key list` is empty on a fresh tenant, and that is normal.** The first key is created lazily, on
  the first token or JWKS request. The command says so in a line — without it "empty" reads as a
  failure.
* **CIO on Kotlin/Native cannot do TLS.** The very first `https://` request fails with "TLS sessions
  are not supported on Native platform". This went unnoticed for six weeks because the CLI only
  talked to its own management port through a port-forward — that is, over plain HTTP. It surfaced
  on the first call to another provider. The engine is now chosen by the platform: **Darwin** on
  macOS (the system stack, nothing for an operator to install) and **Curl** on Linux. The price is
  that the Linux binary needs libcurl in the system and stops being fully self-contained; Ktor has
  no TLS-capable alternative on native.
* **A forgotten `kubectl port-forward` hijacks the port and looks like a broken server.** An
  end-to-end run failed on its first command with a 401 while the code was fine: an old forward to a
  *cluster* shildik was still listening, and requests with the local token went there. The diagnosis
  took half an hour and only broke open on comparing "200 from inside the container, 401 from
  outside". A quick check for an inexplicable 401: `lsof -nP -iTCP:<port> -sTCP:LISTEN` — only
  docker should be there.
* **An error message lied.** A TLS refusal while reading Keycloak was printed as "could not reach
  SHILDIK_URL — check the port-forward": the catch-all in the API command covers everything. Worth
  remembering when reading an operator's complaint.
* **Kotlin/Native compiler caches break linking on Linux.** `clikt` and `clikt-mordant` put
  `selfAndAncestors` into both cache archives, and `ld.lld` fails on the duplicate symbol. On macOS
  it is invisible: cross-compilation does not use the caches. The workaround disables the cache for
  the native binaries, pinned to the Kotlin version so that a bump forces a look.

## 6. Code anchors

| What | Where |
|---|---|
| Commands | `cli/src/commonMain/kotlin/.../cli/Commands.kt` |
| `export` / `apply` | `cli/src/commonMain/kotlin/.../cli/ExportApply.kt` |
| The admin API client | `cli/src/commonMain/kotlin/.../cli/AdminClient.kt` |
| URLs and DTOs (shared with the server) | `shared/src/commonMain/kotlin/.../shared/Admin.kt` |
| Output (interface + Mordant + json) | `cli/src/commonMain/kotlin/.../cli/Output.kt` |
| Files through posix | `cli/src/nativeMain/kotlin/.../cli/Files.native.kt` |
