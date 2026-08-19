package ru.workinprogress.shildik.core.config

/**
 * The configuration without which the service has no right to start.
 *
 * Secrets deliberately have no defaults: a default for a secret is a way to reach production with
 * the default secret. A missing mandatory value brings the start down rather than surfacing on the
 * first request (see [require]).
 */
data class ShildikConfig(
    val issuer: String,
    val publicPort: Int,
    val managementPort: Int,
    /**
     * Master keys. The first is the current one and encrypts; the rest are accepted while
     * decrypting until `shildik key reencrypt` has run (research §R13).
     */
    val masterKeys: List<String>,
    val jdbcUrl: String = "jdbc:postgresql://localhost:5432/shildik",
    val dbUser: String = "shildik",
    val dbPassword: String = "",
    /** The bootstrap token: when unset, it is generated at start-up (research §R8). */
    val bootstrapToken: String? = null,
) {
    /**
     * The token that is actually accepted. When none is given from outside, a random one for this
     * run.
     *
     * Generated here rather than at an entry point: this way every way of raising the service
     * (production, tests, docker compose) behaves identically, and nowhere does a temptation appear
     * to substitute a default.
     */
    val effectiveBootstrapToken: String =
        bootstrapToken?.takeIf { it.isNotBlank() } ?: ru.workinprogress.shildik.crypto.Secrets
            .generate()

    init {
        require(issuer.isNotBlank()) { "issuer is required" }
        require(publicPort in 1..65535) { "publicPort out of range: $publicPort" }
        require(managementPort in 1..65535) { "managementPort out of range: $managementPort" }
        require(managementPort != publicPort) {
            "managementPort equals the public one ($publicPort): the management contour must " +
                "live on a separate port, otherwise it cannot be closed off from the outside network"
        }
        require(masterKeys.isNotEmpty() && masterKeys.none { it.isBlank() }) {
            "SHILDIK_MASTER_KEYS is required: at least one non-empty key"
        }
        require(jdbcUrl.isNotBlank()) { "jdbcUrl is required" }
    }
}
