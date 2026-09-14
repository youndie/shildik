# The image for a Kotlin/Native distribution: the binary is compiled here, and what runs it is
# nothing at all.
#
# One Dockerfile for every distribution. The three arguments are what they differ by — a module, the
# executable it links, and the schema that belongs to the storage it was built against. Two
# Dockerfiles would drift; ours did once, and the one that had lost a library did not fail at build
# time, it failed when the pod started.
#
#     docker build -f docker/native.Dockerfile \
#       --build-arg MODULE=distribution \
#       --build-arg BINARY=shildik.kexe \
#       --build-arg MIGRATIONS=storage-sqlx4k/src/commonMain/resources/migrations \
#       -t shildik:dev .
#
# The context is the REPOSITORY ROOT, not a directory Gradle assembled — `:distribution:image`
# passes exactly the arguments above.

# ---------- Stage 1: the build ----------
#
# THE BINARY IS COMPILED IN THE IMAGE, and it did not used to be. Gradle built it on the runner and
# this file only copied it in, which is faster — the Gradle and `~/.konan` caches survive between
# runs, and here nothing is kept: about four and a half minutes on a standard GitHub runner against
# the 3m15s the cached build took, and roughly three times that on a busy machine. What the cache
# bought was a runtime image, and a runtime image is what made the static link impossible: linking
# statically on the runner would pair the RUNNER's glibc with a donor image's, which is the coupling
# the old version of this file spent a paragraph warning about (#48). Compile here and both halves
# come from one apt index — the pairing problem is not solved, it is gone.
#
# `gradle:9.7.1-jdk25-noble` rather than the wrapper: the tag already carries the version
# `gradle/wrapper/gradle-wrapper.properties` names, and `./gradlew` inside the image would download
# a second copy of it. The two move together — a wrapper bump is a tag bump here.
FROM --platform=linux/amd64 gradle:9.7.1-jdk25-noble AS build

# g++ for the static archives the link needs — `libc.a`, `crt1.o`, `libstdc++.a`, `libgcc.a`,
# `libgcc_eh.a` — and `zlib1g-dev` for `libz.a`, which `ktor-client-curl` asks for by name. The
# image ships glibc 2.39 and not one of these archives; without them the link fails with `unable to
# find library -lc`, which reads like a linker-flag mistake and is not one.
RUN apt-get update \
    && apt-get install -y --no-install-recommends g++ zlib1g-dev \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /src
# `.git` IS IN THE CONTEXT ON PURPOSE, and `.dockerignore` says why: the `kore.build` plugin reads
# it for the commit `/version` reports.
COPY . .

ARG MODULE=distribution
# `-Pshildik.staticLink=true` is where the runtime image goes away; see the `staticLink` block in
# the module's `build.gradle.kts` for what it does and what it costs.
RUN gradle ":${MODULE}:linkReleaseExecutableLinuxX64" --no-daemon -Pshildik.staticLink=true

# ---------- Stage 2: the runtime ----------
#
# NO BASE IMAGE. `gcr.io/distroless/cc-debian12` used to be under this, with `libz.so.1` carried
# across from a `debian:bookworm-slim` donor; the binary is linked statically now, so both are gone.
#
# STATIC DOES NOT MEAN SELF-CONTAINED, and the four glibc files copied here are not caution. glibc
# has no character-set converters built in at all — even UTF-8 arrives from a gconv module that
# `iconv_open` loads with `dlopen` — and Ktor's charset layer on Kotlin/Native *is* glibc `iconv`.
#
# HOW FAR SUCH AN IMAGE GETS WAS MEASURED, by building this same file with the gconv line deleted:
# it starts, answers `/health` and `/ready` with 200, reports `/version`, takes every admin call,
# hashes a password, AND RENDERS THE SIGN-IN PAGE. It fails when the form is posted — the redirect
# carrying the authorization code is built with `encodeURLParameter`, and that is the call that
# needs a converter:
#
#     kotlin.IllegalArgumentException: Failed to open iconv for charset UTF-8 with error code 22
#
# So a smoke test that stops at a status code, or even at a rendered page, passes on an image that
# cannot sign anybody in. `dev/image-smoke.sh` goes all the way to the code.
#
# THE FOUR COME FROM THE BUILD STAGE, and that is the load-bearing word: the shared glibc a static
# binary `dlopen`s has to be the same build as the `libc.a` it was linked against. A copy of the
# same version out of another image is not the same build, and fails in exactly the same way.
#
# The CA bundle is the fifth, and it is this service's own rather than a rule from elsewhere:
# `auth-google` opens an `HttpClient` over `ktor-client-curl`, which carries its own statically
# linked OpenSSL, and `strings` on the binary shows that OpenSSL's compiled-in default —
# `/etc/ssl/certs/ca-certificates.crt`. Without the file, every outbound TLS handshake fails, which
# arrives as Google sign-in refusing for no stated reason. `distroless/cc` carried the bundle, so
# nothing until now had to name this dependency out loud. The smoke test does not reach it: it would
# have to talk to Google.
#
# `/usr/share/zoneinfo` is NOT copied, and that is checked rather than assumed: nothing in this
# repository calls `TimeZone.currentSystemDefault()`, and every timestamp it writes is an epoch
# second or a UTC instant. The day a named zone is formatted, this is where it is remembered.
#
# What `scratch` costs: no shell, no `ls`, nothing to `kubectl exec` into, and no `/tmp`. The one
# thing it was expected to cost and does not is the SQLite file's directory: the process creates the
# parent of `SHILDIK_DB_PATH` itself, checked by starting this image with no volume at all.
FROM scratch
WORKDIR /app

COPY --from=build /etc/ld.so.cache /etc/ld.so.cache
COPY --from=build /lib/x86_64-linux-gnu/ld-linux-x86-64.so.2 /lib/x86_64-linux-gnu/ld-linux-x86-64.so.2
COPY --from=build /lib/x86_64-linux-gnu/libc.so.6 /lib/x86_64-linux-gnu/libc.so.6
COPY --from=build /usr/lib/x86_64-linux-gnu/gconv /usr/lib/x86_64-linux-gnu/gconv
COPY --from=build /etc/ssl/certs/ca-certificates.crt /etc/ssl/certs/ca-certificates.crt

# Inside the image the binary is always `/app/shildik`: an entrypoint must not depend on which
# distribution was built.
ARG MODULE=distribution
ARG BINARY=shildik.kexe
COPY --from=build /src/${MODULE}/build/bin/linuxX64/releaseExecutable/${BINARY} /app/shildik

# The schema travels as files: sqlx4k's `migrate` reads a directory from the filesystem, not
# resources. It is taken from the storage module this binary depends on — the same files the code
# applies at start-up, so the image cannot ship a schema that was never compiled against.
ARG MIGRATIONS=storage-sqlx4k/src/commonMain/resources/migrations
COPY --from=build /src/${MIGRATIONS} /app/migrations
ENV SHILDIK_MIGRATIONS=/app/migrations

# 8080 is the public contour, 9000 the management one. Publish the second at your peril: the
# admin API lives there, and it is the whole access model.
EXPOSE 8080 9000

ENTRYPOINT ["/app/shildik"]
