package ru.workinprogress.shildik.crypto

/**
 * base64url without padding — [RFC 7515 §2](https://www.rfc-editor.org/rfc/rfc7515#section-2).
 *
 * Written here rather than taken from `java.util.Base64`: this is common code, and a JVM class
 * would shut the native targets out. The alphabet differs from plain base64 in two characters
 * (`-_` instead of `+/`) and `=` is never written — which is not cosmetic when signing: a stray
 * `=` changes the bytes that went under the signature.
 */
private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"

fun ByteArray.encodeBase64Url(): String {
    val out = StringBuilder((size + 2) / 3 * 4)
    var i = 0
    while (i + 2 < size) {
        val n =
            (this[i].toInt() and 0xFF shl 16) or
                (this[i + 1].toInt() and 0xFF shl 8) or
                (this[i + 2].toInt() and 0xFF)
        out.append(ALPHABET[n ushr 18 and 0x3F])
        out.append(ALPHABET[n ushr 12 and 0x3F])
        out.append(ALPHABET[n ushr 6 and 0x3F])
        out.append(ALPHABET[n and 0x3F])
        i += 3
    }
    when (size - i) {
        1 -> {
            val n = this[i].toInt() and 0xFF shl 16
            out.append(ALPHABET[n ushr 18 and 0x3F])
            out.append(ALPHABET[n ushr 12 and 0x3F])
        }

        2 -> {
            val n = (this[i].toInt() and 0xFF shl 16) or (this[i + 1].toInt() and 0xFF shl 8)
            out.append(ALPHABET[n ushr 18 and 0x3F])
            out.append(ALPHABET[n ushr 12 and 0x3F])
            out.append(ALPHABET[n ushr 6 and 0x3F])
        }
    }
    return out.toString()
}

fun String.decodeBase64Url(): ByteArray {
    val out = ArrayList<Byte>(length / 4 * 3)
    var buffer = 0
    var bits = 0
    for (ch in this) {
        val value = ALPHABET.indexOf(ch)
        require(value >= 0) { "Illegal character in base64url: '$ch'" }
        buffer = buffer shl 6 or value
        bits += 6
        if (bits >= 8) {
            bits -= 8
            out.add((buffer ushr bits and 0xFF).toByte())
        }
    }
    return out.toByteArray()
}
