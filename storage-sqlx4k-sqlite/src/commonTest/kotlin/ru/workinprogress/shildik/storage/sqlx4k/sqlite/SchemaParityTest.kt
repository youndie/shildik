package ru.workinprogress.shildik.storage.sqlx4k.sqlite

import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readString
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The two schemas are one schema, written twice.
 *
 * Every repository is shared between the adapters, so a column that exists on Postgres and not
 * here is not a difference between two storages — it is a query that works on one installation and
 * fails on the other, found by whoever runs SQLite rather than by a build. The dialects differ (a
 * type, a constraint's place in the file) and this test knows that; what it holds is the part that
 * must not differ.
 *
 * **What it compares:** the tables, their columns, and which of those are `NOT NULL`; the indexes,
 * by name and by the columns they cover. **What it does not:** types, primary and foreign keys.
 * Those are stated differently by necessity — SQLite has no `ALTER TABLE … ADD CONSTRAINT` — and a
 * parser clever enough to compare them across both spellings would be a second source of failures
 * rather than a check. The comment at the top of each schema file says what was translated and why.
 */
class SchemaParityTest {
    @Test
    fun `both schemas declare the same tables and columns`() {
        assertEquals(schemaOf("SHILDIK_TEST_PG_MIGRATIONS"), schemaOf("SHILDIK_TEST_MIGRATIONS"))
    }

    @Test
    fun `both schemas declare the same indexes`() {
        assertEquals(indexesOf("SHILDIK_TEST_PG_MIGRATIONS"), indexesOf("SHILDIK_TEST_MIGRATIONS"))
    }

    private fun schemaOf(variable: String): Map<String, Set<String>> =
        buildMap {
            CREATE_TABLE.findAll(sqlOf(variable)).forEach { table ->
                val name = table.groupValues[1].lowercase()
                val columns =
                    table.groupValues[2]
                        .split('\n')
                        .map { it.trim().trimEnd(',') }
                        .filter { it.isNotEmpty() && !it.startsWith("--") && !it.isConstraint() }
                        .map { line ->
                            val column = line.substringBefore(' ')
                            // The nullability travels with the column: it is the half of a type
                            // that both dialects spell the same way, and the half that changes
                            // what a repository reads back.
                            if (line.contains("not null", ignoreCase = true)) "$column not null" else column
                        }.toSet()
                put(name, columns)
            }
        }

    private fun indexesOf(variable: String): Map<String, String> =
        CREATE_INDEX
            .findAll(sqlOf(variable))
            .associate { index ->
                val (name, table, columns) = index.destructured
                name.lowercase() to "$table(${columns.split(',').joinToString(",") { it.trim() }})".lowercase()
            }

    /** Every migration in a set, in one string: which file a table arrived in is not the point. */
    private fun sqlOf(variable: String): String {
        val directory = Path(requireNotNull(env(variable)) { "$variable is not set by the build" })
        return SystemFileSystem
            .list(directory)
            .filter { it.name.endsWith(".sql") }
            .sortedBy { it.name }
            .joinToString("\n") { file ->
                SystemFileSystem.source(file).buffered().use { it.readString() }
            }
    }

    private fun String.isConstraint(): Boolean = CONSTRAINT_STARTS.any { startsWith(it, ignoreCase = true) }

    private companion object {
        private val CREATE_TABLE = Regex("""create table (\w+) \(([^;]*?)\n\)""", RegexOption.IGNORE_CASE)

        // `USING btree` is Postgres's and has no counterpart here, so it is skipped rather than
        // compared: one kind of index is all SQLite has.
        private val CREATE_INDEX =
            Regex("""create index (\w+) on (\w+)(?: using btree)? \(([^)]+)\)""", RegexOption.IGNORE_CASE)

        private val CONSTRAINT_STARTS = listOf("primary key", "foreign key", "unique", "constraint", "check")
    }
}
