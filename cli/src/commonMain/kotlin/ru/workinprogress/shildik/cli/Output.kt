package ru.workinprogress.shildik.cli

import com.github.ajalt.mordant.rendering.TextColors
import com.github.ajalt.mordant.table.table
import com.github.ajalt.mordant.terminal.Terminal

/**
 * How a result is printed.
 *
 * The interface exists for R11: Mosaic is not wired in, but if a command with a live frame ever
 * shows up, the replacement is an implementation rather than a rewrite of the commands. Commands
 * do not know what prints their output.
 */
interface Output {
    fun table(
        headers: List<String>,
        rows: List<List<String>>,
    )

    fun record(fields: List<Pair<String, String>>)

    fun secret(
        label: String,
        value: String,
    )

    /**
     * A created client together with its secret — as **one** record.
     *
     * A separate method rather than `record` + `secret`, because in json this has to be a single
     * object: two JSON objects in a row are parsed by no consumer at all. Found in an e2e run
     * where `--output json` printed two lines while creating a client.
     */
    fun createdClient(
        clientId: String,
        roles: List<String>,
        secret: String,
    )

    fun message(text: String)
}

/** Machine-readable output. Required from the first version: the CLI lands in scripts before it lands in hands. */
class JsonOutput : Output {
    override fun table(
        headers: List<String>,
        rows: List<List<String>>,
    ) {
        val items =
            rows.joinToString(",") { row ->
                headers.zip(row).joinToString(",", "{", "}") { (h, v) -> "\"$h\":${v.jsonQuoted()}" }
            }
        println("[$items]")
    }

    override fun record(fields: List<Pair<String, String>>) {
        println(fields.joinToString(",", "{", "}") { (k, v) -> "\"$k\":${v.jsonQuoted()}" })
    }

    override fun secret(
        label: String,
        value: String,
    ) = record(listOf(label to value))

    override fun createdClient(
        clientId: String,
        roles: List<String>,
        secret: String,
    ) = record(listOf("clientId" to clientId, "roles" to roles.joinToString(" "), "secret" to secret))

    override fun message(text: String) = record(listOf("message" to text))
}

/** Human-facing output on Mordant — it arrives as a dependency of Clikt, no separate one needed. */
class TerminalOutput(
    private val terminal: Terminal = Terminal(),
) : Output {
    override fun table(
        headers: List<String>,
        rows: List<List<String>>,
    ) {
        if (rows.isEmpty()) {
            terminal.println(TextColors.gray("empty"))
            return
        }
        terminal.println(
            table {
                header { row(*headers.toTypedArray()) }
                body { rows.forEach { row(*it.toTypedArray()) } }
            },
        )
    }

    override fun record(fields: List<Pair<String, String>>) {
        fields.forEach { (k, v) -> terminal.println("${TextColors.gray("$k:")} $v") }
    }

    override fun secret(
        label: String,
        value: String,
    ) {
        // The secret is visible once in the whole life of a client — warn right here, not in
        // documentation nobody is reading at this moment.
        terminal.println(TextColors.yellow("$label: $value"))
        terminal.println(TextColors.gray("Save it: the secret is never shown again — only rotated."))
    }

    override fun createdClient(
        clientId: String,
        roles: List<String>,
        secret: String,
    ) {
        record(listOf("clientId" to clientId, "roles" to roles.joinToString(" ")))
        secret("secret", secret)
    }

    override fun message(text: String) = terminal.println(text)
}

private fun String.jsonQuoted() = "\"" + replace("\\", "\\\\").replace("\"", "\\\"") + "\""
