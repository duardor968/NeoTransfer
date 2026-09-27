package dev.duardo.neotransfer.backup

import java.io.ByteArrayOutputStream
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PendingEncryptedExportTest {
    @Test fun `ciphertext survives a new owner and expires without creating empty output`() {
        val file = Files.createTempFile("nt-export", ".enc").toFile()
        var now = System.currentTimeMillis()
        try {
            val first = PendingEncryptedExport(file, 32, 1_000) { now }
            val encrypted = byteArrayOf(1, 2, 3, 4)
            first.save(encrypted)
            val recreated = PendingEncryptedExport(file, 32, 1_000) { now }
            val output = ByteArrayOutputStream()
            recreated.copyTo(output)
            assertContentEquals(encrypted, output.toByteArray())
            now += 1_001
            recreated.expireIfNeeded()
            assertFalse(recreated.valid())
            assertFalse(file.exists())
        } finally { file.delete() }
    }

    @Test fun `missing truncated or oversized ciphertext is rejected before output`() {
        val file = Files.createTempFile("nt-export", ".enc").toFile()
        val store = PendingEncryptedExport(file, 4, 1_000)
        try {
            assertFailsWith<IllegalArgumentException> { store.save(byteArrayOf(1, 2, 3, 4, 5)) }
            store.clear()
            val output = ByteArrayOutputStream()
            assertFailsWith<IllegalStateException> { store.copyTo(output) }
            assertTrue(output.size() == 0)
            store.save(byteArrayOf(1, 2))
            file.writeBytes(byteArrayOf())
            assertFailsWith<IllegalStateException> { store.copyTo(output) }
            assertTrue(output.size() == 0)
        } finally { store.clear() }
    }
}
