package ru.workinprogress.shildik.auth.magic

import kotlinx.serialization.json.jsonPrimitive
import ru.workinprogress.shildik.core.feature.auth.AuthMethod
import ru.workinprogress.shildik.core.feature.auth.AuthRequest
import ru.workinprogress.shildik.core.feature.auth.AuthenticatedSubject
import ru.workinprogress.shildik.crypto.Hs256
import kotlin.time.Clock

/**
 * Signing in through a link from an email.
 *
 * The email itself, the codes and the rate limiting stay in the application that sends it — that
 * is the product side, and it works. Our part is one thing: verify the handoff JWT the
 * application issued in exchange for the code, and say whose address it is.
 *
 * The token is signed with a **shared secret** rather than with our key: it is not a provider's
 * signature but an assertion by a trusted party — "ownership of this address has been confirmed".
 * Hence `emailVerified = true` on the resulting identity: linking by email is allowed only to
 * whoever actually checked it.
 */
class MagicLinkAuthMethod(
    private val secret: String,
    private val clock: Clock = Clock.System,
) : AuthMethod {
    override val id = ID

    override suspend fun authenticate(request: AuthRequest): AuthenticatedSubject? {
        val token = request[TOKEN_PARAM]?.takeIf { it.isNotBlank() } ?: return null
        val claims = Hs256.verify(token, secret) ?: return null

        // The lifetime is checked here: `Hs256` answers for the signature only. Thirty seconds
        // is what the sender allows for the redirect — an expired token means the link was
        // forwarded or opened later.
        val exp = claims["exp"]?.jsonPrimitive?.content?.toLongOrNull() ?: return null
        if (exp <= clock.now().epochSeconds) return null

        val email = claims["email"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() } ?: return null

        // `externalId` is the email: this method has no other identifier. It does **not** create
        // a person who already exists again — linking goes by the confirmed address, and
        // ownership is proven: the letter went to that address and the code came back from it.
        return AuthenticatedSubject(externalId = email, email = email, emailVerified = true)
    }

    companion object {
        const val ID = "magic"

        /** The parameter name is set by the client side: `signIn(..., { handoff_token })`. */
        const val TOKEN_PARAM = "handoff_token"
    }
}
