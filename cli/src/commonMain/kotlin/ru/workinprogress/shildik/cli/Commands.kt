package ru.workinprogress.shildik.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.core.NoOpCliktCommand
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.multiple
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.choice
import kotlinx.coroutines.runBlocking

/**
 * The root command. The URL and the token are shared by every subcommand: they say **where** and
 * **with what**, not what to do.
 */
class Shildik : NoOpCliktCommand(name = "shildik") {
    override fun help(context: com.github.ajalt.clikt.core.Context) =
        "Manage shildik. The management contour listens on a separate port and is never exposed — " +
            "run this from somewhere that can reach it."
}

abstract class ApiCommand(
    name: String,
) : CliktCommand(name = name) {
    private val url by option("--url", envvar = "SHILDIK_URL", help = "Management port URL")
        .default("http://127.0.0.1:9000")
    private val token by option("--token", envvar = "SHILDIK_TOKEN", help = "Management contour token")
        .required()

    /**
     * `json` is the machine-readable output. It also turns on via `SHILDIK_OUTPUT=json`: in a
     * script, appending the flag to every single command is a nuisance.
     */
    private val outputFormat by option("--output", envvar = "SHILDIK_OUTPUT", help = "human | json")
        .choice("human", "json")
        .default("human")
    protected val tenant by option("--tenant", envvar = "SHILDIK_TENANT").default("main")

    protected val out: Output get() = if (outputFormat == "json") JsonOutput() else TerminalOutput()

    /**
     * Domain errors are printed as they are and yield a **non-zero** exit code: otherwise a CI
     * step named "create the client" stays green while no client was created
     * (services/shildik-cli.md §4).
     */
    protected fun run(block: suspend (AdminClient) -> Unit) {
        runBlocking {
            try {
                block(AdminClient(url, token))
            } catch (e: AdminApiException) {
                out.message("Error ${e.status}: ${e.message}")
                throw ProgramResult(1)
            } catch (e: ProgramResult) {
                throw e
            } catch (e: Throwable) {
                // Network, response parsing, anything else. Without this the CLI dumps a Kotlin
                // stack trace for "server unreachable" — it shows neither the address nor what to do.
                out.message(
                    "Could not reach $url: ${e.message ?: e::class.simpleName}\n" +
                        "The management port is not exposed — check the port-forward, or run this " +
                        "from inside the cluster.",
                )
                throw ProgramResult(1)
            }
        }
    }
}

class TenantList : ApiCommand("list") {
    override fun run() =
        run { api ->
            out.table(listOf("realm"), api.listTenants().map { listOf(it.realm) })
        }
}

class TenantCreate : ApiCommand("create") {
    private val realm by argument("realm")

    // A closed tenant is created by an explicit flag: a contour that admits only provisioned
    // people is a decision, not a default somebody forgot about (feature-closed-registration).
    private val closed by option("--closed", help = "admit provisioned users only").flag()

    override fun run() =
        run { api ->
            val tenant = api.createTenant(realm, registrationOpen = !closed)
            out.record(listOf("realm" to tenant.realm, "registration" to if (tenant.registrationOpen) "open" else "closed"))
        }
}

class ClientList : ApiCommand("list") {
    override fun run() =
        run { api ->
            out.table(
                // The third column means different things for different clients, hence the
                // neutral name: a "roles" header above a list of URLs is a lie in the output.
                listOf("clientId", "type", "roles / redirect"),
                api.listClients(tenant).map {
                    listOf(
                        it.clientId,
                        if (it.public) "public" else "service",
                        if (it.public) it.redirectUris.joinToString(" ") else it.roles.joinToString(" "),
                    )
                },
            )
        }
}

class ClientCreate : ApiCommand("create") {
    private val clientId by argument("clientId")
    private val roles by option("--role", help = "Client role, repeatable").multiple()

    /**
     * A browser client. It gets no secret: a public client has none at all, and an "empty secret"
     * would be worse than no secret (api/protocol-oidc-browser.md §3).
     */
    private val public by option("--public", help = "Browser client: PKCE instead of a secret").flag()
    private val redirectUris by option("--redirect-uri", help = "Where to return the code; matched exactly").multiple()

    override fun run() =
        run { api ->
            val created = api.createClient(tenant, clientId, roles, public, redirectUris)
            val secret = created.secret
            if (secret == null) {
                out.record(
                    listOf(
                        "clientId" to created.clientId,
                        "type" to "public",
                        "redirect" to redirectUris.joinToString(" "),
                    ),
                )
            } else {
                out.createdClient(created.clientId, created.roles.sorted(), secret)
            }
        }
}

class ClientRotateSecret : ApiCommand("rotate-secret") {
    private val clientId by argument("clientId")

    override fun run() =
        run { api ->
            val secret = api.rotateSecret(tenant, clientId).secret
            // A public client has no secret — there is nothing to rotate, and a silent "null"
            // here would read as success.
            secret?.let { out.secret("secret", it) } ?: out.message("The client is public: it has no secret")
        }
}

/**
 * Import a secret issued by the previous provider.
 *
 * The secret is read from stdin, not from an argument: an argument is visible in `ps` and settles
 * in the shell history. The command exists for the migration — so that the switch comes down to a
 * single ingress and no service has to be touched at all (deploy.md §4a).
 */
class ClientImportSecret : ApiCommand("import-secret") {
    private val clientId by argument("clientId")

    override fun run() =
        run { api ->
            val secret = readLine()?.trim().orEmpty()
            if (secret.isEmpty()) {
                out.message("The secret is read from stdin: echo -n '<secret>' | shildik client import-secret <clientId>")
                throw CliktError()
            }
            val updated = api.importSecret(tenant, clientId, secret)
            out.record(listOf("clientId" to updated.clientId, "secret" to "accepted"))
        }
}

/**
 * Set a person's password.
 *
 * The password is read from stdin for the same reason as a client secret: an argument is visible
 * in `ps` and settles in the shell history. There is no self-service — the internal contour has a
 * handful of people, and an administrator does the reset (research-internal-login §2).
 */
class UserSetPassword : ApiCommand("set-password") {
    private val userId by argument("userId")

    override fun run() =
        run { api ->
            val password = readLine()?.trim().orEmpty()
            if (password.isEmpty()) {
                out.message("The password is read from stdin: echo -n '<password>' | shildik user set-password <userId>")
                throw CliktError()
            }
            api.setPassword(tenant, userId, password)
            out.record(listOf("userId" to userId, "password" to "set"))
        }
}

class UserList : ApiCommand("list") {
    override fun run() =
        run { api ->
            out.table(
                listOf("id", "email", "enabled", "identities"),
                api.listUsers(tenant).map { user ->
                    listOf(
                        user.id,
                        user.email ?: "—",
                        if (user.enabled) "yes" else "no",
                        user.identities.joinToString(" ") { it.provider }.ifBlank { "—" },
                    )
                },
            )
        }
}

/**
 * Move people over from the previous provider.
 *
 * The secret is read from the environment, not from an argument: an argument is visible in `ps`
 * and stays in the shell history — the same rule as for `client import-secret`.
 */
class UserImport : ApiCommand("import") {
    private val keycloakUrl by option("--from-keycloak", envvar = "KEYCLOAK_URL", help = "Previous provider URL")
        .required()
    private val keycloakRealm by option("--from-realm", envvar = "KEYCLOAK_REALM", help = "Previous provider realm")
    private val keycloakClientId by option("--from-client", envvar = "KEYCLOAK_CLIENT_ID").default("billing")
    private val keycloakSecret by option(envvar = "KEYCLOAK_CLIENT_SECRET", help = "From the environment only").required()

    override fun run() =
        run { api ->
            val source =
                KeycloakSource(
                    baseUrl = keycloakUrl.trimEnd('/'),
                    realm = keycloakRealm ?: tenant,
                    clientId = keycloakClientId,
                    clientSecret = keycloakSecret,
                )

            val users = source.users()
            var changed = 0
            users.forEach { user ->
                if (api.importUser(tenant, user).changed) changed++
            }

            // The report separates "imported" from "already there": a second run has to be
            // observably empty, otherwise there is nothing to confirm idempotence with.
            out.record(
                listOf(
                    "read" to users.size.toString(),
                    "imported" to changed.toString(),
                    "alreadyThere" to (users.size - changed).toString(),
                ),
            )
        }
}

class ClientSetRoles : ApiCommand("set-roles") {
    private val clientId by argument("clientId")
    private val roles by option("--role").multiple(required = true)

    override fun run() =
        run { api ->
            val updated = api.setRoles(tenant, clientId, roles)
            out.record(listOf("clientId" to updated.clientId, "roles" to updated.roles.joinToString(" ")))
        }
}

class ClientDelete : ApiCommand("delete") {
    private val clientId by argument("clientId")

    override fun run() =
        run { api ->
            api.deleteClient(tenant, clientId)
            out.message("Deleted: $clientId")
        }
}

class KeyList : ApiCommand("list") {
    override fun run() =
        run { api ->
            val keys = api.listKeys(tenant)

            out.table(
                listOf("kid", "state", "retiringSince"),
                keys.map { listOf(it.kid, it.state, it.retiringSince?.toString() ?: "—") },
            )

            // An empty list on a fresh tenant is not a fault: the first key is created lazily,
            // on the first token or JWKS request. Without this line, "empty" reads as a failure.
            if (keys.isEmpty()) {
                out.message("No keys yet: the first one is created on the first token or JWKS request.")
            }
        }
}

class KeyRotate : ApiCommand("rotate") {
    override fun run() =
        run { api ->
            val rotated = api.rotateKey(tenant)
            out.record(listOf("kid" to rotated.kid))
            out.message("The previous key stays in JWKS for a day — clients cache keys.")
        }
}

class KeyRetire : ApiCommand("retire") {
    private val kid by argument("kid")

    override fun run() =
        run { api ->
            api.retireKey(tenant, kid)
            out.message("Key retired: $kid")
        }
}

/**
 * The second step of a master-key change (research §R13): right after a rollout with the new key
 * first in the list, the records are still encrypted with the previous one. Until this command has
 * run, the old key must stay in the configuration.
 */
class KeyReencrypt : ApiCommand("reencrypt") {
    override fun run() =
        run { api ->
            val report = api.reencryptKeys()
            out.record(
                listOf(
                    "reencrypted" to report.reencrypted.toString(),
                    "untouched" to report.untouched.toString(),
                ),
            )
            out.message("Done. The old master key can now be dropped from SHILDIK_MASTER_KEYS.")
        }
}

fun shildikCommand(): CliktCommand =
    Shildik().subcommands(
        NoOpCliktCommand(name = "tenant").subcommands(TenantList(), TenantCreate()),
        NoOpCliktCommand(name = "client").subcommands(
            ClientList(),
            ClientCreate(),
            ClientRotateSecret(),
            ClientSetRoles(),
            ClientImportSecret(),
            ClientDelete(),
        ),
        NoOpCliktCommand(name = "user").subcommands(UserList(), UserImport(), UserSetPassword()),
        NoOpCliktCommand(name = "key").subcommands(KeyList(), KeyRotate(), KeyRetire(), KeyReencrypt()),
        Export(),
        Plan(),
        Apply(),
    )
