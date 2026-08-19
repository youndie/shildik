# The image for a Kotlin/Native distribution.
#
# Deliberately thin: the binary is built by Gradle (`linkReleaseExecutableLinuxX64`) and placed
# into the context next to this file, rather than compiled inside a stage here. Building inside
# the Dockerfile is simpler to read and throws away the Gradle cache on every run.
#
# One Dockerfile for every distribution, with the binary's name as an argument. Two of them drift:
# ours did, and the one that had lost a library did not fail at build time — it failed when the
# pod started.

# A stage for two libraries: distroless carries neither `libcrypt.so.1` (Kotlin/Native links it
# unconditionally) nor `libz.so.1`. The list came from `ldd` against a real binary; when a new
# dependency appears, run `ldd` again rather than guess.
FROM --platform=linux/amd64 debian:bookworm-slim AS libs

FROM --platform=linux/amd64 gcr.io/distroless/cc-debian12

COPY --from=libs /lib/x86_64-linux-gnu/libcrypt.so.1 /lib/x86_64-linux-gnu/
COPY --from=libs /lib/x86_64-linux-gnu/libz.so.1 /lib/x86_64-linux-gnu/

# The binary's name is the only thing distributions differ by. Inside the image it is always
# `/app/shildik`: an entrypoint must not depend on which distribution was built.
ARG BINARY=shildik.kexe
COPY ${BINARY} /app/shildik

# The schema travels as files: sqlx4k's `migrate` reads a directory from the filesystem, not
# resources. A layer of its own — a schema changes less often than a binary.
COPY migrations /app/migrations
ENV SHILDIK_MIGRATIONS=/app/migrations

# 8080 is the public contour, 9000 the management one. Publish the second at your peril: the
# admin API lives there, and it is the whole access model.
EXPOSE 8080 9000

ENTRYPOINT ["/app/shildik"]
