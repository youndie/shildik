package ru.workinprogress.shildik.cli

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.plugins.resources.Resources
import io.ktor.client.plugins.resources.delete
import io.ktor.client.plugins.resources.get
import io.ktor.client.plugins.resources.post
import io.ktor.client.plugins.resources.put
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import ru.workinprogress.shildik.shared.AdminResource
import ru.workinprogress.shildik.shared.ClientView
import ru.workinprogress.shildik.shared.ClientWithSecret
import ru.workinprogress.shildik.shared.CreateClientRequest
import ru.workinprogress.shildik.shared.CreateTenantRequest
import ru.workinprogress.shildik.shared.ErrorView
import ru.workinprogress.shildik.shared.ImportSecretRequest
import ru.workinprogress.shildik.shared.ImportUserRequest
import ru.workinprogress.shildik.shared.ImportedUserView
import ru.workinprogress.shildik.shared.KeyView
import ru.workinprogress.shildik.shared.ReencryptView
import ru.workinprogress.shildik.shared.SetAudiencesRequest
import ru.workinprogress.shildik.shared.SetPasswordRequest
import ru.workinprogress.shildik.shared.SetRolesRequest
import ru.workinprogress.shildik.shared.SetScopesRequest
import ru.workinprogress.shildik.shared.TenantView
import ru.workinprogress.shildik.shared.UserView

class AdminApiException(
    val status: Int,
    override val message: String,
) : Exception(message)

/**
 * The admin API client.
 *
 * URLs and models come from `:shared` — the same description the server builds its routes from.
 * This used to assemble JSON from strings and parse it by field name: a typo in a field name
 * surfaced at runtime, and a divergence from the server never surfaced at all.
 *
 * There is still no validation here: `clientId` uniqueness, whether a role is allowed and the
 * order of rotation are domain invariants (research §R6).
 */
class AdminClient(
    baseUrl: String,
    token: String,
) {
    private val json =
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

    private val http =
        HttpClient(platformHttpEngine()) {
            install(Resources)
            install(ContentNegotiation) { json(json) }
            defaultRequest {
                url(baseUrl.trimEnd('/') + "/")
                header("Authorization", "Bearer $token")
            }
        }

    private val tenants = AdminResource.Tenants()

    private fun tenant(realm: String) = AdminResource.Tenants.ByTenant(tenants, realm)

    private fun clients(realm: String) = AdminResource.Tenants.ByTenant.Clients(tenant(realm))

    private fun client(
        realm: String,
        clientId: String,
    ) = AdminResource.Tenants.ByTenant.Clients
        .ByClient(clients(realm), clientId)

    private fun keys(realm: String) = AdminResource.Tenants.ByTenant.Keys(tenant(realm))

    suspend fun listTenants(): List<TenantView> = http.get(tenants).decode()

    suspend fun createTenant(
        realm: String,
        registrationOpen: Boolean = true,
    ): TenantView = http.post(tenants) { jsonBody(CreateTenantRequest(realm, registrationOpen)) }.decode()

    suspend fun listClients(realm: String): List<ClientView> = http.get(clients(realm)).decode()

    suspend fun createClient(
        realm: String,
        clientId: String,
        roles: List<String>,
        public: Boolean = false,
        redirectUris: List<String> = emptyList(),
        audiences: List<String> = emptyList(),
        scopes: List<String> = emptyList(),
    ): ClientWithSecret =
        http
            .post(clients(realm)) {
                jsonBody(CreateClientRequest(clientId, roles, public, redirectUris, audiences, scopes))
            }.decode()

    suspend fun rotateSecret(
        realm: String,
        clientId: String,
    ): ClientWithSecret =
        http
            .post(
                AdminResource.Tenants.ByTenant.Clients.ByClient
                    .Secret(client(realm, clientId)),
            ).decode()

    /** Import of a secret from the previous provider (deploy.md §4a). */
    suspend fun setPassword(
        realm: String,
        userId: String,
        password: String,
    ) {
        http
            .put(
                AdminResource.Tenants.ByTenant.Users.Password(
                    AdminResource.Tenants.ByTenant.Users(tenant(realm)),
                    userId,
                ),
            ) { jsonBody(SetPasswordRequest(password)) }
            .ensureSuccess()
    }

    suspend fun importSecret(
        realm: String,
        clientId: String,
        secret: String,
    ): ClientView =
        http
            .put(
                AdminResource.Tenants.ByTenant.Clients.ByClient
                    .ImportSecret(client(realm, clientId)),
            ) {
                jsonBody(ImportSecretRequest(secret))
            }.decode()

    suspend fun listUsers(realm: String): List<UserView> =
        http.get(AdminResource.Tenants.ByTenant.Users(tenant(realm))).decode()

    suspend fun importUser(
        realm: String,
        request: ImportUserRequest,
    ): ImportedUserView =
        http
            .post(AdminResource.Tenants.ByTenant.Users(tenant(realm))) {
                jsonBody(request)
            }.decode()

    suspend fun setAudiences(
        realm: String,
        clientId: String,
        audiences: List<String>,
    ): ClientView =
        http
            .put(
                AdminResource.Tenants.ByTenant.Clients.ByClient
                    .Audiences(client(realm, clientId)),
            ) {
                jsonBody(SetAudiencesRequest(audiences))
            }.decode()

    suspend fun setScopes(
        realm: String,
        clientId: String,
        scopes: List<String>,
    ): ClientView =
        http
            .put(
                AdminResource.Tenants.ByTenant.Clients.ByClient
                    .ScopesResource(client(realm, clientId)),
            ) {
                jsonBody(SetScopesRequest(scopes))
            }.decode()

    suspend fun setRoles(
        realm: String,
        clientId: String,
        roles: List<String>,
    ): ClientView =
        http
            .put(
                AdminResource.Tenants.ByTenant.Clients.ByClient
                    .Roles(client(realm, clientId)),
            ) {
                jsonBody(SetRolesRequest(roles))
            }.decode()

    suspend fun deleteClient(
        realm: String,
        clientId: String,
    ) {
        http.delete(client(realm, clientId)).ensureSuccess()
    }

    suspend fun listKeys(realm: String): List<KeyView> = http.get(keys(realm)).decode()

    suspend fun rotateKey(realm: String): KeyView =
        http
            .post(
                AdminResource.Tenants.ByTenant.Keys
                    .Rotate(keys(realm)),
            ).decode()

    suspend fun retireKey(
        realm: String,
        kid: String,
    ) {
        http
            .post(
                AdminResource.Tenants.ByTenant.Keys
                    .Retire(keys(realm), kid),
            ).ensureSuccess()
    }

    suspend fun reencryptKeys(): ReencryptView = http.post(AdminResource.ReencryptKeys()).decode()

    private fun HttpRequestBuilder.jsonBody(body: Any) {
        contentType(ContentType.Application.Json)
        setBody(body)
    }

    private suspend fun HttpResponse.ensureSuccess(): HttpResponse {
        if (status.isSuccess()) return this

        // The domain message is carried through as it is: it explains why the request was
        // refused, and inventing our own wording means diverging from the server.
        val text = bodyAsText()
        val reason = runCatching { json.decodeFromString(ErrorView.serializer(), text).error }.getOrNull()
        throw AdminApiException(status.value, reason?.ifBlank { null } ?: text.ifBlank { "error ${status.value}" })
    }

    private suspend inline fun <reified T> HttpResponse.decode(): T = ensureSuccess().body()
}
