package io.github.youndie.shildik.oidc

import io.github.youndie.shildik.shared.RealmResource
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.resources.href
import io.ktor.resources.serialization.ResourcesFormat
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.coroutines.cancellation.CancellationException

/**
 * Where the provider keeps its keys — asked, not assumed.
 *
 * Composing the address from a known path shape was fine while every provider had the same one.
 * It stopped being fine the moment the shape started moving: a provider may serve
 * `oauth2/jwks`, or the Keycloak-shaped `protocol/openid-connect/certs`, or something else
 * entirely, and a library that hardcodes one of them decides for the operator which providers
 * they are allowed to use.
 *
 * So the address comes from the discovery document — `jwks_uri` is exactly the field that exists
 * for this. The composed path stays as a fallback for a provider that has no discovery, and the
 * fallback is the inherited shape rather than ours: something without discovery is almost
 * certainly older, not newer.
 *
 * **A failed discovery is not cached.** Only an answer is. A provider that is down at start-up
 * would otherwise be remembered as "no discovery" for the lifetime of the process, and the
 * fallback would quietly become permanent.
 *
 * The same document names the provider's `issuer` — the value its tokens carry in `iss` and the one
 * a relying service compares them against. Asked once together with `jwks_uri`; without discovery
 * the inherited `{base}/realms/{realm}` shape stands in, for the same reason as the JWKS fallback.
 */
internal class EndpointAddresses(
    private val client: HttpClient,
    private val base: String,
    private val realm: String,
) {
    private val mutex = Mutex()
    private var discovered: JsonObject? = null

    suspend fun jwksUrl(): String = document()?.text("jwks_uri") ?: certsUrl(base, realm)

    suspend fun issuer(): String = document()?.text("issuer") ?: issuerUrl(base, realm)

    private suspend fun document(): JsonObject? {
        discovered?.let { return it }

        return mutex.withLock {
            discovered?.let { return@withLock it }

            // Отмена — не «дискавери не ответил»: без этого отменённый запрос молча уводил в
            // запасной путь, как если бы провайдер оказался недоступен.
            @Suppress(
                "ktlint:kapkan:swallowed-failure",
                "недоступное дискавери и есть повод взять составленный адрес, а не отказать",
            )
            val fromDiscovery =
                try {
                    val body = client.get(discoveryUrl(base, realm)).bodyAsText()
                    (Json.parseToJsonElement(body) as JsonObject).takeIf { it.text("jwks_uri") != null }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Throwable) {
                    null
                }

            fromDiscovery?.also { discovered = it }
        }
    }

    private fun JsonObject.text(name: String): String? =
        (this[name] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }
}

/** `{base}/realms/{realm}` — the inherited issuer shape, used when a provider has no discovery. */
internal fun issuerUrl(
    base: String,
    realm: String,
): String = base.trimEnd('/') + href(ResourcesFormat(), RealmResource(realm))

/** `{issuer}/.well-known/openid-configuration` — the address a client derives from the issuer. */
internal fun discoveryUrl(
    base: String,
    realm: String,
): String = base.trimEnd('/') + href(ResourcesFormat(), RealmResource.Discovery(RealmResource(realm)))
