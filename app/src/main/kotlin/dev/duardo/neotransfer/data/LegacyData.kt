package dev.duardo.neotransfer.data

import android.content.Context
import java.io.File
import java.security.KeyStore

/**
 * 1.0 starts clean: what the previous versions (up to 1.0.0-dev.6, same package) left behind is
 * deleted on the first start of this one, before its own database exists. Nothing is migrated.
 */
object LegacyData {
    private val databases = listOf("wallet.db", "enzona.db")
    private val preferences = listOf("bank_state", "bank_vault", "permissions", "appearance", "notifications")

    fun clearOnFirstStart(context: Context) {
        if (!context.getDatabasePath(NeoDatabase.NAME).exists()) clear(context)
    }

    fun clear(context: Context) {
        databases.forEach(context::deleteDatabase)
        preferences.forEach(context::deleteSharedPreferences)
        File(context.noBackupFilesDir, "enzona-session.bin").delete()
        File(context.cacheDir, "neotransfer-pending-export.enc").delete()
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        listOf("dev.duardo.neotransfer.bank-pins", "dev.duardo.neotransfer.enzona-session", "${context.packageName}.fuel.coupons.v1")
            .filter(keyStore::containsAlias).forEach(keyStore::deleteEntry)
    }
}
