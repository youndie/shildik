package ru.workinprogress.shildik.cli

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.refTo
import platform.posix.SEEK_END
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fread
import platform.posix.fseek
import platform.posix.ftell
import platform.posix.fwrite
import platform.posix.rewind

/**
 * Files through posix: pulling in okio for two operations is not worth it, and native has no
 * `java.io`.
 */
@OptIn(ExperimentalForeignApi::class)
actual fun readFile(path: String): String {
    val file = fopen(path, "rb") ?: error("Could not open file: $path")
    try {
        fseek(file, 0, SEEK_END)
        val size = ftell(file).toInt()
        rewind(file)

        val buffer = ByteArray(size + 1)
        fread(buffer.refTo(0), 1u, size.toULong(), file)
        return buffer.decodeToString(0, size)
    } finally {
        fclose(file)
    }
}

@OptIn(ExperimentalForeignApi::class)
actual fun writeFile(
    path: String,
    content: String,
) {
    val file = fopen(path, "wb") ?: error("Could not open file for writing: $path")
    try {
        val bytes = content.encodeToByteArray()
        fwrite(bytes.refTo(0), 1u, bytes.size.toULong(), file)
    } finally {
        fclose(file)
    }
}
