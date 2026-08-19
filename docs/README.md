# docs

The provider's documentation: what it does, what it puts on the wire, and how it is managed.

| Layer | Folder | Answers |
|---|---|---|
| Feature | `features/` | what the system does and why, with the scenarios that count as acceptance |
| API | `api/` | contracts: the protocol and the HTTP surface |
| Tooling | [`cli.md`](cli.md) | the admin CLI, its rules and its quirks |

**The invariant: these documents describe what exists.** A plan lives in a task, not here; a
document that describes an intention says so in its `status`. Where a decision cost something, the
document says what it cost — the failures in here are the reason the rules above them exist.

## Where to start

* **A service that consumes tokens** — [api/protocol-oidc-subset](api/protocol-oidc-subset.md),
  then [features/feature-service-auth](features/feature-service-auth.md).
* **A front end that signs people in** — [api/protocol-oidc-browser](api/protocol-oidc-browser.md).
* **Running one** — [cli.md](cli.md) and [api/endpoint-admin](api/endpoint-admin.md).
* **Migrating from Keycloak** — [features/feature-user-import](features/feature-user-import.md),
  which is mostly about a mistake that costs an owner their account.

## What is not here

Deployment runbooks, Helm charts and the post-mortem of our own migration describe **an
installation** rather than the provider, and live in the private repository this code came from.
The reasoning that belongs to the code is in the code: these documents cover the contract and the
behaviour, and the comments cover the decisions.
