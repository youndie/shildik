package ru.workinprogress.shildik.core.feature.auth

/**
 * A way for a user to sign in.
 *
 * **The reason this project happened instead of another `magic-link-spi.jar`.** In Keycloak the
 * magic link lives as a class inside somebody else's process: your own jar built against their SPI
 * contract, your own classloader, a manual build — and lost sources (research §R5).
 *
 * Here a sign-in method is an ordinary module implementing this interface. To add your own: write
 * an implementation, register it with one line where the distribution is assembled, switch it on in
 * the configuration. No jar dropped into somebody else's directory, no debugging inside somebody
 * else's runtime.
 *
 * Registration is **explicit, in code**, not through `ServiceLoader`: that one is JVM-only (it
 * breaks shared code) and hides the dependency graph in the classpath just as well.
 */
interface AuthMethod {
    /** The identifier used in configuration and URLs: `/auth/{id}/...`. */
    val id: String

    /**
     * Checks what was presented and returns a confirmed identity — or `null` when it confirmed
     * nothing.
     *
     * The method does **not** decide whether this person may sign in and does **not** create a
     * session: it answers only the question "who is this". Everything else is the shared sign-in
     * code, identical for every method. Otherwise each new method would rewrite the rules from
     * scratch — which is exactly how divergences appear in other people's SPIs.
     */
    suspend fun authenticate(request: AuthRequest): AuthenticatedSubject?
}

/**
 * A sign-in method that needs a **redirect to another provider**.
 *
 * A plain [AuthMethod] answers "who is this" from what was presented — that is how the magic link
 * works: the code from the email arrived, was checked, was answered. Google cannot do that: the
 * person has to be sent there first, and their identity is learned only when they come back.
 *
 * This surfaced while implementing M-42: the interface designed in M-43 around "check what was
 * presented" did not cover an external provider. Splitting it turned out to be more honest than
 * stretching one method over two different scenarios: for redirecting methods `authenticate`
 * receives the parameters of the **return**, not of the original request.
 */
interface RedirectingAuthMethod : AuthMethod {
    /**
     * Where to send the person.
     *
     * @param callbackUri the address the provider will return them to us at
     * @param state our request identifier; the provider returns it unchanged, and by it we find
     *   what the person had started doing
     */
    fun authorizationUrl(
        callbackUri: String,
        state: String,
    ): String
}

/**
 * A sign-in method that needs **a page of ours**.
 *
 * Google takes the person away to itself ([RedirectingAuthMethod]); the magic link checks what was
 * presented silently. The password needs a third thing: to ask for a login and a password here, and
 * therefore to park the request and show a form.
 *
 * A marker without methods: what exactly to render is the server's knowledge, not the method's.
 * Otherwise a method would start depending on HTML and could not be tested without a server.
 */
interface InteractiveAuthMethod : AuthMethod

/**
 * A sign-in in transport-independent terms: a method need not know about Ktor for it to be testable
 * without a server.
 */
class AuthRequest(
    val realm: String,
    val parameters: Map<String, String>,
) {
    operator fun get(name: String): String? = parameters[name]
}

/**
 * Who signed in.
 *
 * `externalId` is the identifier in the method's own terms (for Google it is `sub`, for the magic
 * link it is the email). The same value goes into the `sub` of the issued token, and that is
 * exactly why a migration from Keycloak must put **their** identifiers here: the pair (`sub`,
 * `iss`) is the linking key in the relying service (research §R10, task M-40).
 */
data class AuthenticatedSubject(
    val externalId: String,
    val email: String?,
    val name: String? = null,
    /**
     * Whether ownership of this email was proven **in this sign-in**.
     *
     * This used to be a property of the method (`provesEmailOwnership`), and that was wrong: Google
     * does not always verify an email — it has an `email_verified` field, and it is sometimes
     * `false`. A property of the method would answer "yes" for such answers too, and linking by an
     * unverified email is account takeover.
     *
     * The magic link always sets `true`: the letter went to the address and the code came back from
     * it. The password sets `false`: an administrator set it, not a mailbox.
     */
    val emailVerified: Boolean = false,
)

/**
 * The registry of sign-in methods.
 *
 * It is assembled where the distribution is assembled and knows only what was put into it. An empty
 * registry is a normal state: in M1–M3 there is no user sign-in at all.
 */
class AuthMethodRegistry(
    methods: List<AuthMethod> = emptyList(),
) {
    private val byId = methods.associateBy { it.id }

    init {
        require(byId.size == methods.size) {
            "Sign-in methods sharing an id: " +
                methods
                    .groupingBy { it.id }
                    .eachCount()
                    .filterValues { it > 1 }
                    .keys
                    .joinToString()
        }
    }

    fun find(id: String): AuthMethod? = byId[id]

    fun ids(): Set<String> = byId.keys
}
