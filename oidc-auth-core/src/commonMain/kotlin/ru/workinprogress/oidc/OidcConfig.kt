package ru.workinprogress.oidc

import kotlinx.serialization.Serializable

@Serializable
data class OidcConfig(
    val realm: String = "",
    val url: String = "",
    val clientId: String = "",
    val secret: String = "",
    /**
     * A second provider whose tokens are also accepted, for the length of a provider migration.
     *
     * A blank string means "the primary one only", and that is the default: the value appears for
     * the migration and is removed afterwards. Tokens are always **issued** by the primary
     * ([url]); the additional provider takes part in signature verification and nothing else.
     */
    val additionalUrl: String = "",
    /** The realm of the additional provider; blank means the same one as the primary. */
    val additionalRealm: String = "",
)
