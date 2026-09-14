# The image for a Kotlin/Native distribution.
#
# Deliberately thin: the binary is built by Gradle (`linkReleaseExecutableLinuxX64`) and placed
# into the context next to this file, rather than compiled inside a stage here. Building inside
# the Dockerfile is simpler to read and throws away the Gradle cache on every run.
#
# One Dockerfile for every distribution, with the binary's name as an argument. Two of them drift:
# ours did, and the one that had lost a library did not fail at build time — it failed when the
# pod started.

# A stage for ONE library: distroless carries no `libz.so.1`, and the binary genuinely needs it.
#
# `libcrypt.so.1` used to be copied here too, and no longer is. Kotlin/Native declares it for every
# Linux program — it arrives with `platform.posix`, whose klib manifest names it — while importing
# not one symbol from it; since `sborka.kmp` links with `-Wl,--as-needed` the declaration is gone.
# Checked rather than assumed, on the real binary:
#
#     readelf -d shildik-sqlite.kexe | grep NEEDED
#       libm.so.6  libpthread.so.0  librt.so.1  libz.so.1  libdl.so.2  libgcc_s.so.1  libc.so.6
#
# Every one of those but `libz` is in distroless/cc. THE COPY IS ALSO WHAT COUPLES THE TWO IMAGES:
# a copied glibc-versioned file makes the donor's glibc have to be no newer than the runtime's, and
# a mismatch builds fine and dies at exec with `GLIBC_2.38 not found`. One file is one coupling
# instead of two; when a new dependency appears, run `readelf -d` again rather than guess.
FROM --platform=linux/amd64 debian:bookworm-slim AS libs

FROM --platform=linux/amd64 gcr.io/distroless/cc-debian12

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
