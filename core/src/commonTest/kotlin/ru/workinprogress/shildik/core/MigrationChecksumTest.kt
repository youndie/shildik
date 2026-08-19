package ru.workinprogress.shildik.core

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A migration's checksum is the `String.hashCode` of its text (that is how sqlx4k 1.13.0 computes
 * it), and it has to be **the same on every platform**.
 *
 * Why this is not idle curiosity: for live databases the history is kept by Flyway, and during the
 * move the "schema applied" row in `_sqlx4k_migrations` is written once — possibly from the JVM. It
 * is a native binary that will check it later. Should the values differ, it decides the file has
 * changed and refuses to start: `Checksum mismatch for migration file`. And that refusal happens
 * **on a rollout**, not on a build.
 *
 * The test lives in `core` rather than in `:storage-sqlx4k`: that one still has a single target,
 * the JVM, where checking that platforms agree is meaningless. Here there are three targets.
 */
class MigrationChecksumTest {
    @Test
    fun `the string hash is the same on every target`() {
        // The values were computed on the JVM and written in as constants: if native computes them
        // differently, the test fails exactly where that matters.
        assertEquals(0, "".hashCode())
        assertEquals(96354, "abc".hashCode())
        assertEquals(-2010433104, "create table tenants (id varchar(64) not null);".hashCode())
        // Cyrillic and a line break — our files contain both.
        assertEquals(-804714719, "-- схема\nselect 1;\n".hashCode())
    }
}
