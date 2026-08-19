package ru.workinprogress.shildik.cli

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.call.body
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import io.ktor.http.Parameters
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import ru.workinprogress.shildik.shared.ExternalIdentityView
import ru.workinprogress.shildik.shared.ImportUserRequest

/**
 * Reading users out of the previous provider.
 *
 * It lives in the CLI rather than in the server on purpose: administrator credentials for
 * **somebody else's** provider have no business showing up in the IdP (feature-user-import §6).
 * The server only knows about its own database; who brought the data and where from is not its
 * concern.
 *
 * The token is obtained with the same `client_credentials` a service client uses against the
 * admin API: no separate administrator has to be provisioned, the service client already has the
 * rights.
 */
class KeycloakSource(
    private val baseUrl: String,
    private val realm: String,
    private val clientId: String,
    private val clientSecret: String,
    // The engine is a parameter only for the pagination tests: standing up a real Keycloak just
    // to check that we read to the end is out of proportion.
    engine: HttpClientEngine? = null,
) {
    private val json = Json { ignoreUnknownKeys = true }

    private val http =
        if (engine == null) {
            HttpClient(platformHttpEngine()) { configureClient() }
        } else {
            HttpClient(engine) { configureClient() }
        }

    private fun HttpClientConfig<*>.configureClient() {
        expectSuccess = false
        install(ContentNegotiation) { json(json) }
    }

    private var token: String? = null

    private suspend fun token(): String =
        token ?: run {
            val response =
                http.submitForm(
                    url = "$baseUrl/realms/$realm/protocol/openid-connect/token",
                    formParameters =
                        Parameters.build {
                            append("grant_type", "client_credentials")
                            append("client_id", clientId)
                            append("client_secret", clientSecret)
                        },
                )
            if (!response.status.isSuccess()) {
                throw AdminApiException(
                    response.status.value,
                    "the previous provider issued no token: ${response.bodyAsText()}",
                )
            }
            response.body<KeycloakToken>().accessToken.also { token = it }
        }

    /**
     * Users, page by page.
     *
     * Paged rather than "give me everything": Keycloak has its own default limit on `/users`, and
     * a request without paging silently returns only the beginning of the list. Silently is the
     * worst part: the import reports success while half the people stay behind.
     */
    suspend fun users(pageSize: Int = 100): List<ImportUserRequest> {
        val collected = mutableListOf<ImportUserRequest>()
        var offset = 0

        while (true) {
            val page =
                http
                    .get("$baseUrl/admin/realms/$realm/users") {
                        header("Authorization", "Bearer ${token()}")
                        parameter("first", offset)
                        parameter("max", pageSize)
                    }.ensureOk("the user list")
                    .body<List<KeycloakUser>>()

            if (page.isEmpty()) return collected

            page.forEach { user -> collected += user.toRequest(identities(user.id)) }
            offset += page.size

            // The provider is free to return fewer than requested on the last page.
            if (page.size < pageSize) return collected
        }
    }

    private suspend fun identities(userId: String): List<ExternalIdentityView> =
        http
            .get("$baseUrl/admin/realms/$realm/users/$userId/federated-identity") {
                header("Authorization", "Bearer ${token()}")
            }.ensureOk("identities of user $userId")
            .body<List<KeycloakFederatedIdentity>>()
            .map { ExternalIdentityView(provider = it.identityProvider, subject = it.userId) }

    private suspend fun io.ktor.client.statement.HttpResponse.ensureOk(what: String) =
        also {
            if (!status.isSuccess()) {
                throw AdminApiException(status.value, "$what: ${status.value} ${bodyAsText()}")
            }
        }
}

@Serializable
private data class KeycloakToken(
    @SerialName("access_token") val accessToken: String,
)

@Serializable
private data class KeycloakUser(
    val id: String,
    val email: String? = null,
    val firstName: String? = null,
    val lastName: String? = null,
    val enabled: Boolean = true,
    val emailVerified: Boolean = false,
) {
    fun toRequest(identities: List<ExternalIdentityView>) =
        ImportUserRequest(
            id = id,
            email = email,
            name = listOfNotNull(firstName, lastName).joinToString(" ").ifBlank { null },
            emailVerified = emailVerified,
            // A disabled person is imported disabled rather than skipped: skipping would
            // silently turn "sign-in denied" into "enabled" (feature-user-import §5).
            enabled = enabled,
            identities = identities,
        )
}

@Serializable
private data class KeycloakFederatedIdentity(
    val identityProvider: String,
    val userId: String,
)
