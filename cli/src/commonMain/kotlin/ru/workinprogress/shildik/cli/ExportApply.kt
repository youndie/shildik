package ru.workinprogress.shildik.cli

import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import kotlinx.serialization.json.Json
import ru.workinprogress.shildik.shared.ExportedClient
import ru.workinprogress.shildik.shared.ExportedConfig
import ru.workinprogress.shildik.shared.ExportedTenant

private val prettyJson =
    Json {
        prettyPrint = true
        encodeDefaults = true
    }

class Export : ApiCommand("export") {
    private val file by option("-f", "--file", help = "Where to write; stdout by default")

    override fun run() =
        run { api ->
            val tenants =
                api.listTenants().map { t ->
                    ExportedTenant(
                        realm = t.realm,
                        clients =
                            api.listClients(t.realm).map {
                                ExportedClient(
                                    clientId = it.clientId,
                                    roles = it.roles,
                                    public = it.public,
                                    redirectUris = it.redirectUris,
                                    audiences = it.audiences,
                                    scopes = it.scopes,
                                )
                            },
                    )
                }

            val text = prettyJson.encodeToString(ExportedConfig.serializer(), ExportedConfig(tenants))
            if (file == null) {
                out.message(text)
            } else {
                writeFile(file!!, text)
                out.message("Written: $file")
            }
        }
}

/** A single change `apply` can make. */
sealed interface Change {
    val summary: String

    data class CreateTenant(
        val realm: String,
    ) : Change {
        override val summary get() = "+ tenant $realm"
    }

    data class CreateClient(
        val realm: String,
        val clientId: String,
        val roles: List<String>,
        val public: Boolean = false,
        val redirectUris: List<String> = emptyList(),
        val audiences: List<String> = emptyList(),
        val scopes: List<String> = emptyList(),
    ) : Change {
        override val summary get() =
            if (public) {
                "+ public client $clientId in $realm [${redirectUris.joinToString(" ")}]"
            } else {
                "+ client $clientId in $realm [${roles.joinToString(" ")}]"
            }
    }

    data class UpdateAudiences(
        val realm: String,
        val clientId: String,
        val from: List<String>,
        val to: List<String>,
    ) : Change {
        override val summary get() = "~ audiences $clientId: [${from.joinToString(" ")}] → [${to.joinToString(" ")}]"
    }

    data class UpdateScopes(
        val realm: String,
        val clientId: String,
        val from: List<String>,
        val to: List<String>,
    ) : Change {
        override val summary get() = "~ scopes $clientId: [${from.joinToString(" ")}] → [${to.joinToString(" ")}]"
    }

    data class UpdateRoles(
        val realm: String,
        val clientId: String,
        val from: List<String>,
        val to: List<String>,
    ) : Change {
        override val summary get() = "~ roles $clientId: [${from.joinToString(" ")}] → [${to.joinToString(" ")}]"
    }

    /**
     * Something extra in the instance. `apply` **does not touch** it — it only reports it.
     *
     * Deleting by file is an operation that wipes live clients on a typo in the path, while no
     * report at all means the configuration in git silently drifts from the instance. The report
     * is the middle ground: the drift is visible, nothing destructive happens (open-questions Q5).
     */
    data class Extra(
        val realm: String,
        val clientId: String,
    ) : Change {
        override val summary get() = "! the instance has an extra client $clientId ($realm) — the file does not describe it"
    }
}

/**
 * Computes the difference between the file and the instance.
 *
 * Shared by `plan` and `apply`: a plan computed by code other than the code that applies it is a
 * plan that will one day disagree with the deed.
 */
suspend fun planChanges(
    api: AdminClient,
    config: ExportedConfig,
): List<Change> {
    val changes = mutableListOf<Change>()
    val existingRealms =
        api.listTenants().map { it.realm }.toSet()

    for (tenant in config.tenants) {
        if (tenant.realm !in existingRealms) {
            changes += Change.CreateTenant(tenant.realm)
            // No tenant means no clients either; there is nothing to ask the server about.
            tenant.clients.forEach {
                changes +=
                    Change.CreateClient(
                        tenant.realm,
                        it.clientId,
                        it.roles.sorted(),
                        it.public,
                        it.redirectUris,
                        it.audiences,
                        it.scopes,
                    )
            }
            continue
        }

        val existing = api.listClients(tenant.realm).associateBy { it.clientId }
        val described = tenant.clients.map { it.clientId }.toSet()

        for (client in tenant.clients) {
            val current = existing[client.clientId]
            if (current == null) {
                changes +=
                    Change.CreateClient(
                        tenant.realm,
                        client.clientId,
                        client.roles.sorted(),
                        client.public,
                        client.redirectUris,
                        client.audiences,
                        client.scopes,
                    )
                continue
            }
            // Both differences are reported, not the first one: a client whose roles and audiences
            // both drifted used to have the second difference hidden by the first, and `apply`
            // would then leave the instance still unlike the file while reporting success.
            if (current.roles.toSet() != client.roles.toSet()) {
                changes += Change.UpdateRoles(tenant.realm, client.clientId, current.roles.sorted(), client.roles.sorted())
            }
            if (current.audiences.toSet() != client.audiences.toSet()) {
                changes +=
                    Change.UpdateAudiences(
                        tenant.realm,
                        client.clientId,
                        current.audiences.sorted(),
                        client.audiences.sorted(),
                    )
            }
            if (current.scopes.toSet() != client.scopes.toSet()) {
                changes +=
                    Change.UpdateScopes(
                        tenant.realm,
                        client.clientId,
                        current.scopes.sorted(),
                        client.scopes.sorted(),
                    )
            }
        }

        existing.keys.filterNot { it in described }.sorted().forEach {
            changes += Change.Extra(tenant.realm, it)
        }
    }
    return changes
}

fun readConfig(path: String): ExportedConfig =
    Json { ignoreUnknownKeys = true }.decodeFromString(ExportedConfig.serializer(), readFile(path))

/**
 * Show what `apply` would do, changing nothing.
 *
 * A separate command rather than a `--dry-run` flag: a plan is what people read before applying,
 * and it must be reachable without the risk of missing the flag.
 */
class Plan : ApiCommand("plan") {
    private val file by option("-f", "--file", help = "Configuration file").required()

    override fun run() =
        run { api ->
            val changes = planChanges(api, readConfig(file))

            if (changes.isEmpty()) {
                out.message("No changes: the instance matches the file.")
                return@run
            }

            out.table(
                listOf("change"),
                changes.map { listOf(it.summary) },
            )

            val extra = changes.count { it is Change.Extra }
            if (extra > 0) {
                out.message(
                    "Extra clients in the instance: $extra. `apply` will not delete them — check " +
                        "them by hand and drop them with `client delete` if they are truly unused.",
                )
            }
        }
}

/**
 * Applying a configuration.
 *
 * **Idempotent**: a second run with the same file changes nothing and does not fail. Otherwise
 * `apply` could not be put into a deployment script, which is the whole point of it.
 *
 * Secrets are not applied: the file has none. A client that did not exist is created — and its
 * secret is printed here exactly once.
 */
class Apply : ApiCommand("apply") {
    private val file by option("-f", "--file", help = "Configuration file").required()

    override fun run() =
        run { api ->
            val config = Json { ignoreUnknownKeys = true }.decodeFromString(ExportedConfig.serializer(), readFile(file))

            val existingRealms =
                api.listTenants().map { it.realm }.toSet()

            for (tenant in config.tenants) {
                if (tenant.realm !in existingRealms) {
                    api.createTenant(tenant.realm)
                    out.message("Tenant created: ${tenant.realm}")
                }

                val existingClients =
                    api.listClients(tenant.realm).associateBy { it.clientId }

                for (client in tenant.clients) {
                    val current = existingClients[client.clientId]
                    if (current == null) {
                        val created =
                            api.createClient(
                                tenant.realm,
                                client.clientId,
                                client.roles,
                                client.public,
                                client.redirectUris,
                                client.audiences,
                                client.scopes,
                            )
                        out.message("Client created: ${client.clientId}")
                        // A public client has no secret — printing "secret: null" would be
                        // worse than staying quiet.
                        created.secret?.let { out.secret("secret:${client.clientId}", it) }
                        continue
                    }

                    // Each difference on its own, not the first one that matched: a client whose
                    // roles and audiences both drifted would otherwise have the second left as it
                    // was, and `apply` would report success over an instance still unlike the file.
                    // Silence when nothing differs — idempotent means "do nothing".
                    if (current.roles.toSet() != client.roles.toSet()) {
                        api.setRoles(tenant.realm, client.clientId, client.roles)
                        out.message("Roles updated: ${client.clientId}")
                    }
                    if (current.audiences.toSet() != client.audiences.toSet()) {
                        api.setAudiences(tenant.realm, client.clientId, client.audiences)
                        out.message("Audiences updated: ${client.clientId}")
                    }
                    if (current.scopes.toSet() != client.scopes.toSet()) {
                        api.setScopes(tenant.realm, client.clientId, client.scopes)
                        out.message("Scopes updated: ${client.clientId}")
                    }
                }
            }
        }
}

/** File I/O is platform-specific: common has none. */
expect fun readFile(path: String): String

expect fun writeFile(
    path: String,
    content: String,
)
