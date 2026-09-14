#!/usr/bin/env bash
# Exercises a running shildik through the paths a `scratch` image can break silently.
#
# The image has no base system under it: no shell, no `ls`, and — unless the Dockerfile copies them
# — no gconv modules and no CA bundle. What that costs is invisible to a status code, and it was
# measured rather than imagined: the same image built without the gconv tree starts, answers
# `/health` and `/ready` with 200, reports `/version`, accepts every call below, hashes a password
# and RENDERS THE SIGN-IN PAGE. It fails on the next step — posting the form — because the redirect
# that carries the authorization code is built with `encodeURLParameter`, and glibc loads the
# converter for that with `dlopen`:
#
#     kotlin.IllegalArgumentException: Failed to open iconv for charset UTF-8 with error code 22
#
# So this script signs a person in and stops at nothing short of the code: it creates a tenant, a
# client and a user through the management API, asks for the sign-in page and reads its HTML, posts
# the form, and requires the redirect that comes back to carry an authorization code. Every step
# before the last one passes on an image that cannot sign anybody in.
#
# Usage: dev/image-smoke.sh [public-url] [management-url]
#        defaults: http://localhost:8080 http://localhost:9000
#        SHILDIK_BOOTSTRAP_TOKEN — the token the container was started with (default `bootstrap`).
#
# THE PUBLIC URL HAS TO BE THE CONTAINER'S `SHILDIK_ISSUER`. Posting the sign-in form is refused
# unless the `Origin` header matches the issuer — there is no cookie session here, so that header is
# the whole CSRF defence — and this script sends the public URL as the origin. Point it at an
# address the container does not consider its own and the run ends in a 403 that is correct.
#
# It assumes A FRESH CONTAINER: it creates the realm `smoke` and fails if it is already there. Run
# it against something just started, not against an installation.
set -euo pipefail

public=${1:-http://localhost:8080}
management=${2:-http://localhost:9000}
token=${SHILDIK_BOOTSTRAP_TOKEN:-bootstrap}

realm=smoke
client=smoke-app
redirect=http://localhost:1/callback
login=smoke@example.test
password=smoke-password-1

admin=(-H "Authorization: Bearer $token" -H 'Content-Type: application/json')

fail() {
    printf 'smoke: %s\n' "$1" >&2
    exit 1
}

printf 'smoke: waiting for %s\n' "$management" >&2
for _ in $(seq 1 60); do
    if curl -fsS -o /dev/null "$management/health" 2>/dev/null; then break; fi
    sleep 1
done
curl -fsS -o /dev/null "$management/health" || fail "the management port never answered on $management"

# `/ready` is the database and the migrations: it is the one probe that can fail for a reason the
# image is responsible for, and it is what says the schema layer arrived.
curl -fsS -o /dev/null "$management/ready" || fail "/ready says the storage is not there"

# A `version:` line also means the object the Gradle plugin generates was compiled in rather than
# merely written to disk — Kotlin/Native has no resources to read it from at run time.
curl -fsS "$management/version" | grep -q '^version: ' || fail "/version did not report a version"

curl -fsS "${admin[@]}" -X POST -d "{\"realm\":\"$realm\"}" \
    "$management/admin/tenants" > /dev/null || fail "creating the tenant failed"

# NOT a public client on purpose: a public one must present PKCE, and computing an S256 challenge
# here would add a step that tests this script rather than the image.
curl -fsS "${admin[@]}" -X POST \
    -d "{\"clientId\":\"$client\",\"redirectUris\":[\"$redirect\"],\"scopes\":[\"openid\"]}" \
    "$management/admin/tenants/$realm/clients" > /dev/null || fail "creating the client failed"

curl -fsS "${admin[@]}" -X POST -d "{\"id\":\"smoke-user\",\"email\":\"$login\"}" \
    "$management/admin/tenants/$realm/users" > /dev/null || fail "importing the user failed"

# PBKDF2 through the platform provider — on Kotlin/Native that is the OpenSSL the cryptography
# library carries, and a static link is exactly where it could have stopped working.
curl -fsS "${admin[@]}" -X PUT -d "{\"password\":\"$password\"}" \
    "$management/admin/tenants/$realm/users/smoke-user/password" > /dev/null \
    || fail "setting the password failed"

# THE RENDERED PAGE. Everything above this line answers with JSON that a broken charset layer can
# still produce; the sign-in form is HTML written through `respondText`.
page=$(curl -fsS -G "$public/realms/$realm/protocol/openid-connect/auth" \
    --data-urlencode "client_id=$client" \
    --data-urlencode "redirect_uri=$redirect" \
    --data-urlencode 'response_type=code' \
    --data-urlencode 'scope=openid' \
    --data-urlencode 'state=smoke-state' 2>/dev/null) \
    || fail "the authorization request did not answer — the sign-in page never rendered"

printf '%s' "$page" | grep -q '<h1>Sign in</h1>' \
    || fail "the response to the authorization request is not the sign-in page: ${page:0:200}"

# The form's `state` is the parked request, and it is not the `state` the client sent.
parked=$(printf '%s' "$page" | grep -oE 'name="state" value="[^"]+"' | head -1 | sed 's/.*value="//; s/"$//')
[ -n "$parked" ] || fail "the sign-in page carries no state — there is nothing to post"

# And the sign-in itself. The redirect that answers this is built with `encodeURLParameter`, which
# is the call that needs a gconv module; a 302 with a `code=` in it is the whole point of the run.
#
# `Origin` is mandatory rather than polite: the handler refuses a form posted from anywhere but the
# issuer's own origin, and a browser would send this header without being asked.
#
# `|| true` because the pipeline ends in `grep`, and a `grep` that matches nothing exits 1 — under
# `set -e -o pipefail` that would end the script here, silently, at the one step whose failure this
# whole file exists to report.
location=$(curl -sS -o /dev/null -D - -X POST \
    -H "Origin: $public" \
    --data-urlencode "state=$parked" \
    --data-urlencode "login=$login" \
    --data-urlencode "password=$password" \
    "$public/realms/$realm/protocol/openid-connect/auth/password/login" \
    | grep -i '^location:' | tr -d '\r' | sed 's/^[Ll]ocation: //' || true)

case "$location" in
    "$redirect"*code=*) printf 'smoke: signed in, code issued — %s\n' "$location" >&2 ;;
    '') fail "signing in produced no redirect at all" ;;
    *) fail "signing in redirected somewhere else: $location" ;;
esac
