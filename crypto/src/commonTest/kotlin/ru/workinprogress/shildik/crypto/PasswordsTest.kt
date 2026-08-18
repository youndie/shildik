package ru.workinprogress.shildik.crypto

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class PasswordsTest {
    // Deliberately few iterations: these tests check the rules rather than the strength, and
    // 210 000 iterations per case would make this the slowest suite in the project.
    private val cheap = 1_000

    @Test
    fun `the right password passes and a wrong one does not`() =
        runTest {
            val stored = Passwords.hash("right", cheap)

            assertTrue(Passwords.verify("right", stored))
            assertFalse(Passwords.verify("wrong", stored))
        }

    @Test
    fun `equal passwords produce different records`() =
        runTest {
            // The salt is random; otherwise the database shows who shares a password with whom.
            assertNotEquals(Passwords.hash("same", cheap), Passwords.hash("same", cheap))
        }

    @Test
    fun `the iteration count is stored in the record`() =
        runTest {
            // So it can be raised without a migration: existing records verify with their own value.
            val stored = Passwords.hash("secret", cheap)

            assertTrue(stored.startsWith("pbkdf2-sha512\$$cheap\$"), "record is not self-describing: $stored")
            assertTrue(Passwords.verify("secret", stored), "a record with an older iteration count must still verify")
        }

    @Test
    fun `a corrupted record refuses without throwing`() =
        runTest {
            // Corruption in the database must look exactly like a wrong password to the caller.
            assertFalse(Passwords.verify("secret", "garbage"))
            assertFalse(Passwords.verify("secret", "pbkdf2-sha512\$notanumber\$aaaa\$bbbb"))
            assertFalse(Passwords.verify("secret", "argon2\$1\$aaaa\$bbbb"))
        }
}
