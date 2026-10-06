# compose-sign-in

The smallest Compose Multiplatform app that signs a person in against shildik: Android, iOS, desktop
and the browser, one screen, with refresh and sign-out. The client is
[kotlin-multiplatform-oidc](https://github.com/kalinjul/kotlin-multiplatform-oidc) 0.18.4 — the version
the app contour was accepted against ([protocol-oidc-app](../../docs/api/protocol-oidc-app.md)).

A build of its own: the provider's build has no reason to resolve the Android and Compose plugins.

## Register the app

One public client with a return address per platform:

```
shildik tenant create sample
shildik client create sample-app --tenant sample --public \
  --redirect-uri http://127.0.0.1/redirect \
  --redirect-uri io.github.youndie.shildik.sample:/callback \
  --redirect-uri http://localhost:8081/redirect
```

| Platform | Return address | How it gets there |
|---|---|---|
| Desktop | `http://127.0.0.1:8935/redirect` | the library listens on that port for the length of a sign-in; registered without a port, so it may change (RFC 8252 §7.3) |
| Android, iOS | `io.github.youndie.shildik.sample:/callback` | Custom Tabs / `ASWebAuthenticationSession` hand the scheme back to the app (RFC 8252 §7.1) |
| Browser | `http://localhost:8081/redirect` | a popup loads this app at `/redirect`, which posts the result to the opener; matched exactly |

Then a person to sign in as. The CLI has no `user create`, so through the management API, the way
`dev/image-smoke.sh` does it — then a password from stdin:

```
curl -H "Authorization: Bearer $SHILDIK_BOOTSTRAP_TOKEN" -H 'Content-Type: application/json' \
  -d '{"id":"person","email":"person@example.test"}' http://localhost:9000/admin/tenants/sample/users
shildik user set-password person --tenant sample
```

## Run

The issuer field defaults to `http://localhost:8080/realms/sample` (`http://10.0.2.2:8080/…` on the
Android emulator — the host machine as the emulator sees it). The issuer must be the address the
server was started with as `SHILDIK_ISSUER`: the sign-in form is refused from any other origin.

```bash
../../gradlew -p . :app:run                          # desktop
../../gradlew -p . :app:wasmJsBrowserDevelopmentRun  # browser, on :8081
../../gradlew -p . :android:installDebug             # Android
```

iOS: build `:app:linkDebugFrameworkIosSimulatorArm64` and host `MainViewController()` from an Xcode
app. There is no Xcode project here.

## What is checked, and how

| | Checked by | Status |
|---|---|---|
| The protocol with this client | `acceptance-kmp-oidc` in the image smoke workflow, on every change | green |
| The sample compiles: desktop, browser, Android | `.github/workflows/sample.yaml` | on every change to the sample |
| iOS compiles | by hand on a Mac | compiles (2026-10-07) |
| A person signs in through each platform's browser | by hand | **not yet done on any platform** |

The last row is the one CI cannot answer: the platform half — the browser, the redirect back to the
app — only exists on a device. Fill in the date per platform when it has been done.
