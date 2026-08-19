package ru.workinprogress.oidc

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import ru.workinprogress.shildik.crypto.Jws
import ru.workinprogress.shildik.crypto.SigningKey
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * A whole identity provider — a key, a JWKS document and token issuance.
 *
 * Tokens are signed with a **real** signature from `crypto` rather than a made-up string: the
 * verification is ours now, and a test that feeds it a pre-approved result would only be checking
 * itself. JWKS requests are intercepted by `MockEngine`, so no network is involved, and the call
 * counter makes the cache visible.
 */
internal class Provider(
    val kid: String,
    val url: String,
    val realm: String = "main",
) {
    private var key: SigningKey? = null
    var offline: Boolean = false

    /** How many times the provider was asked for JWKS — the number that shows whether the cache works. */
    var calls: Int = 0

    /** How many times its discovery document was read. */
    var discoveryCalls: Int = 0

    val certs: String get() = certsUrl(url, realm)

    val discovery: String get() = discoveryUrl(url, realm)

    /**
     * Куда `jwks_uri` в discovery показывает. `null` — провайдер без discovery: так выглядит и
     * тот, кто старше этого поля, и тот, кто в эту секунду недоступен.
     */
    var jwksUri: String? = null

    private suspend fun key(): SigningKey = key ?: SigningKey.generate(kid).also { key = it }

    suspend fun jwks(): String {
        val jwk = key().publicJwk()
        return Json.encodeToString(
            JsonObject.serializer(),
            JsonObject(mapOf("keys" to JsonArray(listOf(jwk)))),
        )
    }

    suspend fun token(
        clientId: String = "billing",
        roles: Set<String> = setOf("orders:read"),
        expiresIn: Duration = 5.minutes,
        email: String? = null,
        signWith: SigningKey? = null,
    ): String {
        val claims =
            buildMap {
                put("iss", JsonPrimitive("$url/realms/$realm"))
                put("sub", JsonPrimitive("service-account-$clientId"))
                put("azp", JsonPrimitive(clientId))
                put(
                    "realm_access",
                    JsonObject(mapOf("roles" to JsonArray(roles.map { JsonPrimitive(it) }))),
                )
                put("exp", JsonPrimitive((Clock.System.now() + expiresIn).epochSeconds))
                email?.let { put("email", JsonPrimitive(it)) }
            }

        return Jws.sign(signWith ?: key(), JsonObject(claims))
    }

    /** A token without `exp`, assembled by hand because [token] always sets one. */
    suspend fun tokenWithoutExpiry(clientId: String = "billing"): String =
        Jws.sign(
            key(),
            JsonObject(
                mapOf(
                    "sub" to JsonPrimitive("service-account-$clientId"),
                    "azp" to JsonPrimitive(clientId),
                ),
            ),
        )

    suspend fun signingKey(): SigningKey = key()
}

/** An engine serving the JWKS — and, when asked for, the discovery — of the listed providers. */
internal fun jwksEngine(vararg providers: Provider) =
    MockEngine { request ->
        val address = request.url.toString()

        providers.firstOrNull { it.discovery == address }?.let { provider ->
            val uri = provider.jwksUri
            return@MockEngine if (uri == null) {
                respondError(HttpStatusCode.NotFound)
            } else {
                provider.discoveryCalls++
                respond(
                    """{"issuer":"${provider.url}/realms/${provider.realm}","jwks_uri":"$uri"}""",
                    headers = headersOf("Content-Type", ContentType.Application.Json.toString()),
                )
            }
        }

        val provider = providers.firstOrNull { it.certs == address || it.jwksUri == address }

        when {
            provider == null -> respondError(HttpStatusCode.NotFound)
            provider.offline -> respondError(HttpStatusCode.ServiceUnavailable)
            else -> {
                provider.calls++
                respond(
                    provider.jwks(),
                    headers = headersOf("Content-Type", ContentType.Application.Json.toString()),
                )
            }
        }
    }
