package ru.workinprogress.oidc

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
import ru.workinprogress.shildik.shared.RealmResource

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
 */
internal class EndpointAddresses(
    private val client: HttpClient,
    private val base: String,
    private val realm: String,
) {
    private val mutex = Mutex()
    private var discovered: String? = null

    suspend fun jwksUrl(): String {
        discovered?.let { return it }

        return mutex.withLock {
            discovered?.let { return@withLock it }

            val fromDiscovery =
                runCatching {
                    val body = client.get(discoveryUrl(base, realm)).bodyAsText()
                    (Json.parseToJsonElement(body) as JsonObject)["jwks_uri"]
                        ?.let { it as? JsonPrimitive }
                        ?.content
                        ?.takeIf { it.isNotBlank() }
                }.getOrNull()

            fromDiscovery?.also { discovered = it } ?: certsUrl(base, realm)
        }
    }
}

/** `{issuer}/.well-known/openid-configuration` — the address a client derives from the issuer. */
internal fun discoveryUrl(
    base: String,
    realm: String,
): String = base.trimEnd('/') + href(ResourcesFormat(), RealmResource.Discovery(RealmResource(realm)))
