package ru.workinprogress.shildik.server.ui

/**
 * The sign-in page. The only HTML in the whole service — and long may it stay that way.
 *
 * It is assembled as a string rather than through `kotlinx.html`: there are fifteen tags of markup
 * here, and dragging a DSL with its own document model into shared code for their sake is not
 * warranted. A second screen would be a reason; this is not.
 *
 * The markup is semantic, without a single class: Pico styles it as it is, so the form contains
 * nothing but the form (research-internal-login §3).
 */
internal object LoginPage {
    /** The URL the embedded Pico is served from. Cached for a long time — it never changes. */
    const val STYLESHEET_PATH = "/assets/pico.classless.min.css"

    enum class Problem {
        /**
         * One message for both "no such login" and "wrong password".
         *
         * Different texts would tell whoever is guessing which addresses exist — the same reason
         * the sign-in method itself answers identically (`PasswordAuthMethod`).
         */
        WRONG,

        /** Attempts exhausted. Honesty is safer here: a person needs to know they must wait. */
        LOCKED,

        /** The request is stale or gone: the form was opened long ago, or sign-in restarted. */
        EXPIRED,
    }

    fun html(
        actionPath: String,
        state: String,
        problem: Problem? = null,
    ): String {
        val message =
            when (problem) {
                Problem.WRONG -> "Wrong login or password"
                Problem.LOCKED -> "Too many attempts. Sign-in is closed for fifteen minutes"
                Problem.EXPIRED -> "The request has expired — start signing in again"
                null -> null
            }

        return buildString {
            append("<!doctype html><html lang=\"en\"><head>")
            append("<meta charset=\"utf-8\">")
            append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">")
            // The sign-in page is neither indexed nor cached: it carries a one-time `state`.
            append("<meta name=\"robots\" content=\"noindex\">")
            append("<title>Sign in</title>")
            append("<link rel=\"stylesheet\" href=\"").append(STYLESHEET_PATH).append("\">")
            append("</head><body><main><article>")
            append("<h1>Sign in</h1>")

            if (message != null) {
                // `role=alert` so that it is read out loud: the message appears after the form is
                // submitted, and without the role a screen reader skips it.
                append("<p role=\"alert\"><strong>").append(escape(message)).append("</strong></p>")
            }

            if (problem != Problem.EXPIRED) {
                append("<form method=\"post\" action=\"").append(escape(actionPath)).append("\">")
                append("<input type=\"hidden\" name=\"state\" value=\"").append(escape(state)).append("\">")
                append("<label>Login<input type=\"text\" name=\"login\" autocomplete=\"username\" required autofocus></label>")
                append(
                    "<label>Password<input type=\"password\" name=\"password\" " +
                        "autocomplete=\"current-password\" required></label>",
                )
                append("<button type=\"submit\">Sign in</button>")
                append("</form>")
            }

            append("</article></main></body></html>")
        }
    }

    /**
     * Escaping is mandatory even though every value here is ours: we generate the `state` and build
     * the path ourselves. But the rule "only escaped text reaches HTML" must have no exceptions —
     * exceptions outlive the people who remembered why they were safe.
     */
    private fun escape(value: String): String =
        buildString(value.length) {
            value.forEach {
                when (it) {
                    '&' -> append("&amp;")
                    '<' -> append("&lt;")
                    '>' -> append("&gt;")
                    '"' -> append("&quot;")
                    '\'' -> append("&#39;")
                    else -> append(it)
                }
            }
        }
}
