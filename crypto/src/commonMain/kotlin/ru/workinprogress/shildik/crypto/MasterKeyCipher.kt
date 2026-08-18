package ru.workinprogress.shildik.crypto

import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.algorithms.AES
import dev.whyoleg.cryptography.algorithms.SHA256
import dev.whyoleg.cryptography.random.CryptographyRandom

/**
 * Encrypting private signing keys with master keys taken from the environment.
 *
 * A **list** of keys, not one. That list is the whole mechanism for rotating without downtime:
 *
 * 1. the new key goes into the configuration first, the old one second;
 * 2. the service encrypts with the new key and decrypts by trying them in order — existing
 *    records still open with the old key, so nothing breaks at the moment of the rollout;
 * 3. a re-encryption pass rewrites the storage with the new key;
 * 4. the old key is removed from the configuration.
 *
 * Without step two, rotating a key would mean "the service stopped signing": every record is
 * encrypted with the previous key, and the new one does not open them.
 *
 * AES-GCM rather than CBC, because GCM authenticates the ciphertext. That is not decoration here:
 * trying keys in turn relies on it. A key that fits differs from one that does not by the tag
 * matching, not by the plaintext looking plausible.
 */
class MasterKeyCipher(
    masterKeys: List<String>,
) {
    init {
        require(masterKeys.isNotEmpty()) { "At least one master key is required" }
        require(masterKeys.none { it.isBlank() }) { "Blank master key in the list" }
    }

    private val rawKeys = masterKeys

    /** Encryption **always uses the first key**: that one is the current key. */
    suspend fun encrypt(plain: ByteArray): ByteArray = cipher(rawKeys.first()).encrypt(plain)

    /**
     * Decryption tries the keys in list order.
     *
     * The order matters: the current key comes first, so in the steady state the search ends on
     * the first attempt.
     */
    suspend fun decrypt(cipherText: ByteArray): ByteArray {
        var lastFailure: Throwable? = null
        for (key in rawKeys) {
            try {
                return cipher(key).decrypt(cipherText)
            } catch (e: Throwable) {
                lastFailure = e
            }
        }
        throw IllegalStateException(
            "None of the ${rawKeys.size} master keys fit. If a key was rotated, the old one must " +
                "stay in the configuration until the re-encryption pass has run.",
            lastFailure,
        )
    }

    /** Whether a record is already encrypted with the current key, so re-encryption can skip it. */
    suspend fun isCurrent(cipherText: ByteArray): Boolean =
        try {
            cipher(rawKeys.first()).decrypt(cipherText)
            true
        } catch (_: Throwable) {
            false
        }

    /**
     * A master key arrives as a string of arbitrary length while AES wants exactly 256 bits, so
     * it goes through SHA-256. This is not key stretching: the value is required to be random
     * already.
     */
    private suspend fun cipher(masterKey: String) =
        CryptographyProvider.Default
            .get(AES.GCM)
            .keyDecoder()
            .decodeFromByteArray(
                AES.Key.Format.RAW,
                CryptographyProvider.Default
                    .get(SHA256)
                    .hasher()
                    .hash(masterKey.encodeToByteArray()),
            ).cipher()

    companion object {
        fun randomMasterKey(): String = CryptographyRandom.nextBytes(32).encodeBase64Url()
    }
}
