package dev.duardo.neotransfer.enzona

import android.app.KeyguardManager
import android.content.Context
import android.content.ContextWrapper
import android.os.Looper
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import org.json.JSONObject
import java.io.File
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.util.Base64
import java.util.UUID
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory

/** Root runs this on its instrumentation worker with the device already unlocked. No prompts,
 * network, production file access or production alias access. All fixtures are synthetic. */
object EnzonaSessionStorageChecks {
    fun run(context: Context, onPassed: (String) -> Unit = {}): Int {
        check(Looper.myLooper() != Looper.getMainLooper()) { "Run session storage checks on a worker" }
        check(!context.getSystemService(KeyguardManager::class.java).isDeviceLocked) {
            "Session storage checks require an already unlocked device"
        }
        val fixture = Fixture(context)
        var passed = 0
        var bodyFailure: Throwable? = null
        fun case(name: String, body: () -> Unit) {
            body()
            passed++
            onPassed(name)
        }
        try {
            case("ENZONA store construction defers storage access and creates no key") {
                check(fixture.directoryReads == 0)
                check(!fixture.file.exists())
                check(!fixture.keys.containsAlias(fixture.alias))
            }

            case("ENZONA synthetic tokens survive reopening without storing the password or plaintext") {
                fixture.store.save(fixture.tokens)
                val loaded = checkNotNull(fixture.reopen().load())
                assertTokens(fixture.tokens, loaded)
                val serialized = loaded.forEncryptedStorage()
                check(!serialized.contains(fixture.passwordSentinel))
                check(!JSONObject(serialized).has("password"))
                val disk = String(fixture.file.readBytes(), Charsets.ISO_8859_1)
                for (secret in listOf(fixture.tokens.accessToken, fixture.tokens.refreshToken!!,
                    fixture.tokens.idToken, fixture.passwordSentinel)) {
                    check(!disk.contains(secret)) { "A synthetic secret was persisted as plaintext" }
                }
            }

            case("ENZONA session key needs no biometric authentication or user-presence prompt") {
                val key = fixture.keys.getKey(fixture.alias, null) as SecretKey
                val info = SecretKeyFactory.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
                    .getKeySpec(key, KeyInfo::class.java) as KeyInfo
                check(!info.isUserAuthenticationRequired)
                check(!info.isTrustedUserPresenceRequired)
                check(!info.isUserConfirmationRequired)
                check(key.encoded == null)
                check(info.keySize == 256 && KeyProperties.BLOCK_MODE_GCM in info.blockModes)
            }

            case("ENZONA repeated save uses fresh encryption and remains readable") {
                val before = fixture.file.readBytes()
                fixture.store.save(fixture.tokens)
                check(!before.contentEquals(fixture.file.readBytes()))
                assertTokens(fixture.tokens, checkNotNull(fixture.reopen().load()))
            }

            case("ENZONA authenticated ciphertext corruption fails without erasing the file") {
                val corrupted = fixture.file.readBytes().also { bytes ->
                    bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
                }
                fixture.file.writeBytes(corrupted)
                val failure = runCatching { fixture.reopen().load() }.exceptionOrNull()
                check(failure is GeneralSecurityException) { "Modified ciphertext was not rejected by authentication" }
                check(fixture.file.readBytes().contentEquals(corrupted))
                check(fixture.keys.containsAlias(fixture.alias))
                fixture.store.save(fixture.tokens)
                assertTokens(fixture.tokens, checkNotNull(fixture.reopen().load()))
            }

            case("ENZONA truncated session envelope is rejected without implicit deletion") {
                val truncated = byteArrayOf(1, 2)
                fixture.file.writeBytes(truncated)
                check(runCatching { fixture.reopen().load() }.exceptionOrNull() is IllegalArgumentException)
                check(fixture.file.readBytes().contentEquals(truncated))
            }

            case("ENZONA clear is idempotent and preserves another isolated vault and alias") {
                fixture.sibling.save(fixture.tokens)
                val siblingBytes = fixture.siblingFile.readBytes()
                fixture.store.clear()
                fixture.store.clear()
                check(fixture.store.load() == null)
                check(!fixture.file.exists())
                check(!fixture.keys.containsAlias(fixture.alias))
                check(fixture.siblingFile.readBytes().contentEquals(siblingBytes))
                check(fixture.keys.containsAlias(fixture.siblingAlias))
                assertTokens(fixture.tokens, checkNotNull(fixture.sibling.load()))
            }
        } catch (failure: Throwable) {
            bodyFailure = failure
            throw failure
        } finally {
            val cleanupFailure = runCatching { fixture.close() }.exceptionOrNull()
            if (cleanupFailure != null) {
                val original = bodyFailure ?: throw cleanupFailure
                original.addSuppressed(cleanupFailure)
            }
        }
        return passed
    }

    private fun assertTokens(expected: EnzonaTokens, actual: EnzonaTokens) {
        // Never use assertion messages that render credential fields.
        check(actual.accessToken == expected.accessToken)
        check(actual.refreshToken == expected.refreshToken)
        check(actual.idToken == expected.idToken)
        check(actual.expiresAtEpochSeconds == expected.expiresAtEpochSeconds)
        check(actual.accountSubject() == expected.accountSubject())
    }

    private class Fixture(base: Context) : AutoCloseable {
        private val id = UUID.randomUUID().toString()
        private val parent = base.noBackupFilesDir.canonicalFile
        private val directory = File(parent, "enzona-storage-check-$id")
        private val fileName = "session-$id.bin"
        private val siblingName = "sibling-$id.bin"
        val alias = "dev.duardo.neotransfer.test.enzona-session.$id"
        val siblingAlias = "$alias.sibling"
        val file = File(directory, fileName)
        val siblingFile = File(directory, siblingName)
        var directoryReads = 0
            private set
        private val isolated = object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this
            override fun getNoBackupFilesDir(): File { directoryReads++; return directory }
        }
        val store = EnzonaSessionStore(isolated, fileName, alias)
        val sibling = EnzonaSessionStore(isolated, siblingName, siblingAlias)
        val keys: KeyStore by lazy { KeyStore.getInstance("AndroidKeyStore").apply { load(null) } }
        val passwordSentinel = "SYNTHETIC_PASSWORD_NOT_FOR_STORAGE_$id"
        private val payload = Base64.getUrlEncoder().withoutPadding().encodeToString(
            "{\"sub\":\"storage-test-$id@carbon.super\"}".toByteArray())
        val tokens = EnzonaTokens.fromResponse(JSONObject()
            .put("access_token", "SYNTHETIC_ACCESS_$id")
            .put("refresh_token", "SYNTHETIC_REFRESH_$id")
            .put("id_token", "synthetic.$payload.signature")
            .put("token_type", "Bearer").put("expires_in", 3600)
            .put("password", passwordSentinel).toString(), 1000)

        init {
            check(directory.canonicalFile.parentFile == parent)
            check(!directory.exists() && directory.mkdir()) { "Cannot create isolated storage fixture" }
        }

        fun reopen() = EnzonaSessionStore(isolated, fileName, alias)

        override fun close() {
            // Delete only exact owned aliases/files. Never scan aliases or recursively delete a parent.
            check(directory.canonicalFile.parentFile == parent && directory.name == "enzona-storage-check-$id")
            var failure: Throwable? = null
            for (owned in listOf(store to alias, sibling to siblingAlias)) {
                try {
                    check(owned.second.startsWith("dev.duardo.neotransfer.test.enzona-session.$id"))
                    owned.first.clear()
                    check(!keys.containsAlias(owned.second))
                } catch (cleanup: Throwable) {
                    if (failure == null) failure = cleanup else failure.addSuppressed(cleanup)
                }
            }
            try {
                check(directory.listFiles()?.isEmpty() == true && directory.delete()) {
                    "Isolated storage fixture was not fully removed"
                }
            } catch (cleanup: Throwable) {
                if (failure == null) failure = cleanup else failure.addSuppressed(cleanup)
            }
            failure?.let { throw it }
        }
    }
}
