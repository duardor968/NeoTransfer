package dev.duardo.neotransfer.enzona

import org.json.JSONObject
import java.util.Base64

/** Never include this object's contents in logs, state snapshots or user backups. */
internal class EnzonaTokens(
    val accessToken: String,
    val refreshToken: String?,
    val idToken: String,
    val expiresAtEpochSeconds: Long,
) {
    override fun toString(): String = "EnzonaTokens(redacted)"

    fun needsRefresh(nowEpochSeconds: Long): Boolean = expiresAtEpochSeconds - nowEpochSeconds <= 30

    /** Routing hint received over the token backchannel; not an independently verified identity. */
    fun accountSubject(): String = try {
        val parts = idToken.split('.')
        require(parts.size == 3 && parts.all(String::isNotEmpty))
        val payload = JSONObject(String(Base64.getUrlDecoder().decode(parts[1]), Charsets.UTF_8))
        val subject = payload.get("sub")
        require(subject is String && subject.isNotBlank() && subject.none(Char::isISOControl))
        subject
    } catch (_: Exception) {
        throw IllegalArgumentException("La respuesta de acceso no identifica la cuenta")
    }

    fun forEncryptedStorage(): String = JSONObject().apply {
        put("access_token", accessToken)
        put("refresh_token", refreshToken ?: JSONObject.NULL)
        put("id_token", idToken)
        put("expires_at", expiresAtEpochSeconds)
    }.toString()

    companion object {
        fun fromResponse(json: String, nowEpochSeconds: Long, previous: EnzonaTokens? = null): EnzonaTokens = try {
            val value = JSONObject(json)
            require((!value.has("error") || value.isNull("error")) &&
                (!value.has("error_description") || value.isNull("error_description")))
            val access = value.requiredString("access_token")
            require(access.none(Char::isWhitespace))
            require(value.requiredString("token_type").equals("Bearer", ignoreCase = true))
            val lifetime = value.exactLong("expires_in")
            require(lifetime > 0 && nowEpochSeconds >= 0)
            val identity = value.optionalString("id_token") ?: requireNotNull(previous?.idToken)
            val refresh = value.optionalString("refresh_token") ?: previous?.refreshToken
            EnzonaTokens(access, refresh, identity, Math.addExact(nowEpochSeconds, lifetime)).also {
                val subject = it.accountSubject()
                if (previous != null) require(subject == previous.accountSubject())
            }
        } catch (_: Exception) {
            throw IllegalArgumentException("La respuesta de acceso no es válida")
        }

        fun fromEncryptedStorage(json: String): EnzonaTokens = try {
            val value = JSONObject(json)
            val access = value.requiredString("access_token")
            require(access.none(Char::isWhitespace))
            val expiry = value.exactLong("expires_at")
            require(expiry > 0)
            EnzonaTokens(access, value.optionalString("refresh_token"), value.requiredString("id_token"), expiry)
                .also { it.accountSubject() }
        } catch (_: Exception) {
            throw IllegalArgumentException("No se pudo recuperar la sesión")
        }

        private fun JSONObject.requiredString(name: String): String = requireNotNull(optionalString(name))

        private fun JSONObject.optionalString(name: String): String? {
            if (!has(name) || isNull(name)) return null
            val item = get(name)
            require(item is String && item.isNotBlank() && item.none(Char::isISOControl))
            return item
        }

        private fun JSONObject.exactLong(name: String): Long = when (val item = get(name)) {
            is Int -> item.toLong()
            is Long -> item
            else -> throw IllegalArgumentException("Invalid integer")
        }
    }
}
