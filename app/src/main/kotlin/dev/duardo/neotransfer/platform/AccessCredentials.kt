package dev.duardo.neotransfer.platform

import dev.duardo.neotransfer.core.Bank
import org.json.JSONObject

/** Decrypted material is short lived; callers close it and wipe their input buffer. */
class AccessCredentials private constructor(
    val legacy: MutableMap<Bank, CharArray>,
    val scoped: MutableMap<String, CharArray>,
) : AutoCloseable {
    fun encode(): ByteArray = JSONObject().apply {
        put("version", 1)
        put("legacy", JSONObject().apply { legacy.forEach { (bank, pin) -> put(bank.name, String(pin)) } })
        put("accesses", JSONObject().apply { scoped.forEach { (alias, pin) -> put(alias, String(pin)) } })
    }.toString().toByteArray(Charsets.UTF_8)

    override fun close() {
        legacy.values.forEach { it.fill('\u0000') }; legacy.clear()
        scoped.values.forEach { it.fill('\u0000') }; scoped.clear()
    }

    override fun toString(): String = "AccessCredentials"

    companion object {
        fun empty() = AccessCredentials(mutableMapOf(), mutableMapOf())

        fun decode(bytes: ByteArray): AccessCredentials {
            require(bytes.size in 2..65_536) { "Acceso guardado no válido" }
            val result = empty()
            try {
                val root = JSONObject(String(bytes, Charsets.UTF_8))
                val legacy = if (root.has("version")) {
                    require(root.getInt("version") == 1)
                    root.getJSONObject("legacy")
                } else root
                Bank.entries.forEach { bank ->
                    if (legacy.has(bank.name)) {
                        val pin = legacy.getString(bank.name)
                        require(pin.length == bank.pinLength && pin.all { it in '0'..'9' })
                        result.legacy[bank] = pin.toCharArray()
                    }
                }
                require(root.has("version") || result.legacy.isNotEmpty())
                root.optJSONObject("accesses")?.let { accesses ->
                    require(accesses.length() <= 100)
                    for (alias in accesses.keys()) {
                        require(alias.isNotBlank() && alias.length <= 160)
                        val pin = accesses.getString(alias)
                        require(pin.length in 4..32 && pin.all { it in '0'..'9' })
                        result.scoped[alias] = pin.toCharArray()
                    }
                }
                return result
            } catch (failure: Exception) {
                result.close()
                throw IllegalArgumentException("Acceso guardado no válido")
            }
        }
    }
}
