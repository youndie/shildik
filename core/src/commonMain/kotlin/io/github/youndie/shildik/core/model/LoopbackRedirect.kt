package io.github.youndie.shildik.core.model

/**
 * The loopback redirect of RFC 8252 §7.3: `http://127.0.0.1:<port>/…` or `http://[::1]:<port>/…`.
 *
 * Parsed by hand rather than through a URL type, and on purpose: a URL parser normalises — drops a
 * default port, lowercases, resolves dots — and a redirect check that compares normalised forms
 * accepts addresses nobody registered. Here nothing is rewritten except the port being cut out.
 */
internal object LoopbackRedirect {
    private const val SCHEME = "http://"
    private val HOSTS = listOf("127.0.0.1", "[::1]")

    /**
     * The same address with its port removed, if [uri] is a loopback address carrying a port;
     * `null` for anything else, including a loopback address without a port (there is nothing to
     * relax: it either is registered or it is not).
     */
    fun withoutPort(uri: String): String? {
        if (!uri.startsWith(SCHEME)) return null
        val rest = uri.substring(SCHEME.length)
        val host = HOSTS.firstOrNull { rest.startsWith("$it:") } ?: return null
        val afterHost = rest.substring(host.length + 1)
        // The authority ends at the first of these; whatever stands between the colon and it is
        // the port, and it must be digits only — `127.0.0.1:80@evil.example` is not a port.
        val end = afterHost.indexOfFirst { it == '/' || it == '?' || it == '#' }
        val port = if (end < 0) afterHost else afterHost.substring(0, end)
        val tail = if (end < 0) "" else afterHost.substring(end)
        if (port.isEmpty() || port.length > 5 || !port.all { it in '0'..'9' }) return null
        if (port.toInt() !in 1..65535) return null
        return SCHEME + host + tail
    }
}
