package dev.duardo.neotransfer.core.backup

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class BackupEnvelopeTest {
    private val sample = "{\"snapshot\":\"synthetic\",\"pin\":\"1234\"}".toByteArray()

    @Test fun `round trip uses fresh salt and nonce without changing password`() {
        val password = "test-only password".toCharArray()
        val first = BackupEnvelope.encrypt(sample, password)
        val second = BackupEnvelope.encrypt(sample, password)
        assertFalse(first.contentEquals(second))
        assertContentEquals(sample, BackupEnvelope.decrypt(first, password))
        assertContentEquals(sample, BackupEnvelope.decrypt(second, password))
        assertContentEquals("test-only password".toCharArray(), password)
    }

    @Test fun `wrong password and tampering never return plaintext`() {
        val encrypted = BackupEnvelope.encrypt(sample, "right password".toCharArray())
        assertFailsWith<BackupFailure.AuthenticationFailed> {
            BackupEnvelope.decrypt(encrypted, "wrong password".toCharArray())
        }
        encrypted[encrypted.lastIndex] = (encrypted.last().toInt() xor 1).toByte()
        assertFailsWith<BackupFailure.AuthenticationFailed> {
            BackupEnvelope.decrypt(encrypted, "right password".toCharArray())
        }
    }

    @Test fun `truncated oversized and unknown version reject before decryption`() {
        val encrypted = BackupEnvelope.encrypt(sample, "test-only password".toCharArray())
        assertFailsWith<BackupFailure.InvalidInput> {
            BackupEnvelope.decrypt(encrypted.copyOf(encrypted.size - 1), "test-only password".toCharArray())
        }
        assertFailsWith<BackupFailure.InvalidInput> {
            BackupEnvelope.decrypt(ByteArray(BackupEnvelope.maxEnvelopeBytes + 1), "test-only password".toCharArray())
        }
        encrypted[8] = 2
        assertFailsWith<BackupFailure.UnsupportedVersion> {
            BackupEnvelope.decrypt(encrypted, "test-only password".toCharArray())
        }
        assertFailsWith<BackupFailure.PasswordRequired> {
            BackupEnvelope.encrypt(sample, charArrayOf())
        }
    }
}
