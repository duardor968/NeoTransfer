package dev.duardo.neotransfer.backup

import org.json.JSONArray
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class TrmImporterTest {
    @Test fun `synthetic historical container previews table meanings without access hashes`() {
        val source = fixture()
        val preview = TrmImporter.preview(source)
        assertEquals("synthetic", preview.sourceVersion)
        assertEquals(1, preview.records(TrmRecordKind.CONTACT).size)
        assertEquals(1, preview.records(TrmRecordKind.OWN_ACCOUNT).size)
        assertEquals(1, preview.records(TrmRecordKind.RECIPIENT_ACCOUNT).size)
        assertEquals(1, preview.records(TrmRecordKind.PREPAID_CARD).size)
        assertEquals(1, preview.records(TrmRecordKind.RECHARGE_CODE).size)
        assertEquals(1, preview.skippedSensitive["AOperKey"])
        assertEquals(1, preview.unsupported["UnknownTable"])
        assertTrue(preview.records.none { it.legacyTable == "AOperKey" })
    }

    @Test fun `tampered hash and truncated container cannot produce a preview`() {
        val good = fixture()
        val badHash = good.copyOf().also { it[128] = if (it[128] == 'a'.code.toByte()) 'b'.code.toByte() else 'a'.code.toByte() }
        assertFailsWith<TrmFailure.IntegrityFailed> { TrmImporter.preview(badHash) }
        assertFailsWith<TrmFailure> { TrmImporter.preview(good.copyOf(260)) }
        assertFailsWith<TrmFailure.InvalidFile> { TrmImporter.preview(ByteArray(24 * 1024 * 1024 + 1)) }
    }

    private fun fixture(): ByteArray {
        val tables = JSONArray()
        fun add(name: String, row: JSONObject) {
            tables.put(JSONObject().put("tabla", name).put("dataJSON", JSONArray().put(row)))
        }
        add("Client", JSONObject().put("id", 1).put("name", "Persona de prueba"))
        add("MCBank", JSONObject().put("id", 2).put("cuenta", "0000000000000001"))
        add("CuentaBanco", JSONObject().put("id", 3).put("id_client", 1).put("cuenta", "0000000000000002"))
        add("TarjetaPropia", JSONObject().put("id", 4).put("serie", "000000000001"))
        add("Pin", JSONObject().put("id", 5).put("pin", "123456789012"))
        add("AOperKey", JSONObject().put("id", 6).put("pin", "synthetic-hash"))
        add("UnknownTable", JSONObject().put("id", 7))
        val json = JSONObject().put("version_apk", "synthetic").put("scheme", JSONArray()).put("datos", tables)
            .toString().toByteArray(StandardCharsets.UTF_8)
        val keyHex = "a".repeat(128)
        val key = MessageDigest.getInstance("SHA-256").digest(keyHex.toByteArray(StandardCharsets.US_ASCII))
        val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"))
        val encrypted = Base64.getEncoder().encodeToString(cipher.doFinal(json))
        val digest = MessageDigest.getInstance("SHA-512").digest(json).joinToString("") { "%02x".format(it.toInt() and 0xff) }
        return (keyHex + digest + encrypted).toByteArray(StandardCharsets.US_ASCII)
    }
}
