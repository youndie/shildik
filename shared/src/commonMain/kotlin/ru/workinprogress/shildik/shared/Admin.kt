package ru.workinprogress.shildik.shared

import io.ktor.resources.Resource
import kotlinx.serialization.Serializable

/**
 * The management surface. It listens on a separate port, but that is a property of the
 * deployment rather than of the addresses, so only the paths live here.
 */
@Resource("/admin")
class AdminResource {
    @Resource("tenants")
    class Tenants(
        val parent: AdminResource = AdminResource(),
    ) {
        @Resource("{tenant}")
        class ByTenant(
            val parent: Tenants = Tenants(),
            val tenant: String,
        ) {
            @Resource("clients")
            class Clients(
                val parent: ByTenant,
            ) {
                @Resource("{clientId}")
                class ByClient(
                    val parent: Clients,
                    val clientId: String,
                ) {
                    @Resource("secret")
                    class Secret(
                        val parent: ByClient,
                    )

                    /**
                     * Importing a secret issued by a previous provider. A separate address from
                     * `secret`: that one reissues, this one accepts — different operations, and
                     * differently dangerous.
                     */
                    @Resource("import-secret")
                    class ImportSecret(
                        val parent: ByClient,
                    )

                    @Resource("roles")
                    class Roles(
                        val parent: ByClient,
                    )

                    /**
                     * Which resources this client may hold a token for.
                     *
                     * Its own address rather than a field of some general update: changing it
                     * changes which services will accept this client's tokens, and that deserves to
                     * be a request somebody made on purpose.
                     */
                    @Resource("audiences")
                    class Audiences(
                        val parent: ByClient,
                    )

                    /**
                     * What this client's tokens may permit where they are spent.
                     *
                     * Its own address for the same reason as the one above: this decides what a
                     * resource server will let the client do, and that is not a field to change in
                     * passing.
                     */
                    @Resource("scopes")
                    class ScopesResource(
                        val parent: ByClient,
                    )
                }
            }

            /**
             * People. When migrating from a previous provider the identifier comes from outside
             * and is stored verbatim.
             */
            @Resource("users")
            class Users(
                val parent: ByTenant,
            ) {
                /** A password is set through an address of its own: it is not part of a profile and never shown in one. */
                @Resource("{userId}/password")
                class Password(
                    val parent: Users,
                    val userId: String,
                )
            }

            @Resource("keys")
            class Keys(
                val parent: ByTenant,
            ) {
                @Resource("rotate")
                class Rotate(
                    val parent: Keys,
                )

                @Resource("{kid}/retire")
                class Retire(
                    val parent: Keys,
                    val kid: String,
                )
            }
        }
    }

    /**
     * Re-encrypting the keys with the master key is an instance-wide operation rather than a
     * tenant one: there is a single master key for the whole service.
     */
    @Resource("keys/reencrypt")
    class ReencryptKeys(
        val parent: AdminResource = AdminResource(),
    )
}

@Serializable
data class TenantView(
    val realm: String,
    val registrationOpen: Boolean = true,
)

@Serializable
data class ClientView(
    val clientId: String,
    val roles: List<String>,
    val public: Boolean = false,
    val redirectUris: List<String> = emptyList(),
    /** Resources this client may hold a token for (RFC 8707). Empty means its tokens carry no `aud`. */
    val audiences: List<String> = emptyList(),
    /** Permissions this client may hold. Empty means its tokens carry no `scope`. */
    val scopes: List<String> = emptyList(),
)

/** The response to creation and reissue: the **only** place a secret is ever visible. */
@Serializable
data class ClientWithSecret(
    val clientId: String,
    val secret: String? = null,
    val roles: List<String>,
)

@Serializable
data class KeyView(
    val kid: String,
    val state: String,
    val createdAt: Long,
    val retiringSince: Long? = null,
)

@Serializable
data class ReencryptView(
    val reencrypted: Int,
    val untouched: Int,
)

@Serializable
data class CreateTenantRequest(
    val realm: String,
    /**
     * Whether a stranger who proved their identity is let in.
     *
     * For an internal installation — no: infrastructure sits behind the provider there, and
     * owning a Google account must not be enough to reach it.
     */
    val registrationOpen: Boolean = true,
)

@Serializable
data class CreateClientRequest(
    val clientId: String,
    val roles: List<String> = emptyList(),
    /** A public client is a browser one. It gets no secret. */
    val public: Boolean = false,
    val redirectUris: List<String> = emptyList(),
    /**
     * Resources this client may ask a token for (RFC 8707).
     *
     * Absent means its tokens carry no `aud` — which is how every client behaved before this
     * existed, and which a resource server that checks the audience refuses.
     */
    val audiences: List<String> = emptyList(),
    /**
     * Permissions this client may hold.
     *
     * Absent means its tokens carry no `scope` claim — how every client behaved before this
     * existed, and what a resource server built on OAuth refuses.
     */
    val scopes: List<String> = emptyList(),
)

@Serializable
data class ExternalIdentityView(
    val provider: String,
    val subject: String,
)

@Serializable
data class UserView(
    val id: String,
    val email: String? = null,
    val name: String? = null,
    val emailVerified: Boolean = false,
    val enabled: Boolean = true,
    val identities: List<ExternalIdentityView> = emptyList(),
)

/**
 * A migration request. `id` is required and not generated by the server — that is the point of
 * the operation: the foreign identifier is preserved so that applications keep recognising the
 * person.
 */
@Serializable
data class ImportUserRequest(
    val id: String,
    val email: String? = null,
    val name: String? = null,
    val emailVerified: Boolean = false,
    val enabled: Boolean = true,
    val identities: List<ExternalIdentityView> = emptyList(),
)

@Serializable
data class ImportedUserView(
    val id: String,
    /** `false` means such a user already existed, exactly like this. The migration report relies on it. */
    val changed: Boolean,
)

@Serializable
data class ImportSecretRequest(
    val secret: String,
)

@Serializable
data class SetRolesRequest(
    val roles: List<String>,
)

@Serializable
data class SetAudiencesRequest(
    val audiences: List<String>,
)

@Serializable
data class SetScopesRequest(
    val scopes: List<String>,
)

@Serializable
data class ErrorView(
    val error: String,
)

/**
 * The configuration `export` writes and `apply` reads.
 *
 * There are no secrets here and there cannot be: they are never readable back. A placeholder
 * stands in their place so the file stays honest — it shows what has to be supplied and cannot
 * be mistaken for a complete configuration.
 */
@Serializable
data class ExportedConfig(
    val tenants: List<ExportedTenant>,
)

@Serializable
data class ExportedTenant(
    val realm: String,
    val clients: List<ExportedClient>,
)

@Serializable
data class ExportedClient(
    val clientId: String,
    val roles: List<String>,
    /**
     * A placeholder rather than a secret: `export` contains none. A public client has no secret
     * at all — the field stays for the sake of a uniform file.
     */
    val secret: String = SECRET_PLACEHOLDER,
    val public: Boolean = false,
    val redirectUris: List<String> = emptyList(),
    val audiences: List<String> = emptyList(),
    val scopes: List<String> = emptyList(),
)

const val SECRET_PLACEHOLDER = "\${SECRET}"

/**
 * The password arrives **in the body**, not in the path and not in a query parameter: paths and
 * query strings end up in proxy logs, in shell history and in metrics.
 */
@Serializable
data class SetPasswordRequest(
    val password: String,
)
