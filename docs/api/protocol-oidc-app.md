---
id: protocol-oidc-app
title: The app contour of OIDC
type: api
status: active
tags: [identity, oidc, apps]
---

# The app contour

An app — Android, iOS, desktop, or a page that runs the flow itself — signs a person in with the
same authorization code + PKCE as the [browser contour](protocol-oidc-browser.md). What differs is
where the code comes back to. A web front end has an `https` address of its own. An app has either a
port it opened on the person's machine or a URI scheme the operating system routes to it. This
document covers those two, and the client the contour was accepted against.

## 1. Registering an app

An app is a **public** client: it ships to people's devices, so any secret in it would be known to
everyone. It is registered by an administrator; there is no dynamic client registration.

```
shildik client create my-app --public \
  --redirect-uri http://127.0.0.1/callback \
  --redirect-uri io.example.myapp:/callback
```

Ask for `offline_access` in the authorization request to get a refresh token. Without it no refresh
token is issued (protocol-oidc-browser §4a).

## 2. The two return addresses

### Loopback, on any port (desktop) — RFC 8252 §7.3

A desktop app opens a listener on a port the operating system picks at the moment of sign-in, so it
cannot know the port in advance. Register the address **without a port**. For a public client,
`http://127.0.0.1/<path>` or `http://[::1]/<path>` then matches the same address on any port from 1
to 65535.

Nothing else is relaxed:

* `localhost` is a name and can be rebound (§8.3), so it gets exact matching only;
* `https` is not a loopback listener;
* the path and the query match byte for byte;
* a confidential client gets exact matching only.

The code exchange compares the requested address exactly, port included. The relaxation applies to
registration, not to a code moving to another listener.

### A private-use scheme (Android, iOS) — RFC 8252 §7.1

Chrome Custom Tabs and `ASWebAuthenticationSession` return to the app through a scheme the app
claimed, for example `io.example.myapp:/callback` or `io.example.myapp://callback`. The code redirect
carries the registered address as registered: it is built by concatenation and not through a URL
type, which could add a `//` or drop the path for a scheme it does not know.

**The scheme has to be a reversed domain name.** The operating system gives a scheme to whichever
installed app claimed it, and any app can claim `myapp:`. Creating a public client with a dotless
private-use scheme is refused, and the error names the RFC.

## 3. The client it was accepted against

As with the browser contour, acceptance means **somebody else's client** completes the flow against
a running shildik.

That client is [kalinjul/kotlin-multiplatform-oidc](https://github.com/kalinjul/kotlin-multiplatform-oidc)
(Apache-2.0), the library a Kotlin Multiplatform app would use:

| Platform | How it gets the code back | Upstream status |
|---|---|---|
| Android | Chrome Custom Tabs, private-use scheme | stable |
| iOS | `ASWebAuthenticationSession`, private-use scheme | stable |
| Desktop (JVM) | an embedded server on a loopback address | experimental |
| Wasm | a popup that posts the result back to the opener | experimental |

**Accepted version: `oidc-core` 0.18.4**, pinned in `gradle/libs.versions.toml`. The module
`acceptance-kmp-oidc` runs in the image smoke workflow, against the built native image, on both
storages. It drives the library through the steps below twice, once on a loopback port nobody
registered and once on a private-use scheme:

1. `discover()`;
2. `createAuthorizationCodeRequest()` with PKCE S256, `nonce` and `state`;
3. the sign-in form, filled in and posted the way a person would — the only step that is not the
   library's;
4. the code taken off `Location`;
5. `exchangeToken()`, with the `id_token`'s `nonce` and `aud` checked;
6. `refreshToken()`, with rotation checked;
7. end session, both through the browser address (it has to come back to the app) and through
   `endSession()`.

A new version of the library is a new acceptance run, not a dependency refresh.

Each platform needs its own setup in the app: activity and intent filter on Android, URL scheme on
iOS, popup page on Wasm. That setup belongs to the library's documentation: [Android](https://github.com/kalinjul/kotlin-multiplatform-oidc/blob/main/docs/setup-android.md),
[iOS](https://github.com/kalinjul/kotlin-multiplatform-oidc/blob/main/docs/setup-ios.md),
[Wasm](https://github.com/kalinjul/kotlin-multiplatform-oidc/blob/main/docs/setup-wasm.md).

## 4. What the library does not do, and why that is acceptable here

* **It does not verify the `id_token` signature.** For a public client that receives the token
  directly from the token endpoint over TLS, OIDC Core §3.1.3.7 (item 6) permits relying on TLS
  instead. An app that wants the check can use `{issuer}/oauth2/jwks`, which any origin can read.
* **It has no `https` ("claimed") redirects**, such as Android App Links or iOS associated domains.
  shildik accepts such an address — it is an ordinary exact match — but the accepted client does
  not use one.

## 5. What is deliberately absent

* No implicit flow and no resource-owner password grant: the code flow with PKCE is the only way an
  app gets a token.
* No dynamic client registration: an administrator registers an app.
* No wildcard redirects: the loopback port is the only part of an address that varies, and only for
  a public client.
