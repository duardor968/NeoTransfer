package dev.duardo.neotransfer.platform

import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutManager
import android.provider.Telephony
import android.service.quicksettings.TileService
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.duardo.neotransfer.MainActivity
import dev.duardo.neotransfer.data.LegacyData
import dev.duardo.neotransfer.engine.NeoTransferApplication
import dev.duardo.neotransfer.engine.Routes
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** What the system sees of the app: shortcut, tile, SMS receiver, application; and the clean start. */
@RunWith(AndroidJUnit4::class)
class EntryPointsTest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun scanShortcutOpensThisVariantOnTheScanner() {
        val shortcut = context.getSystemService(ShortcutManager::class.java).getShortcuts(ShortcutManager.FLAG_MATCH_MANIFEST).single { it.id == "scan" }
        assertEquals("Escanear", shortcut.shortLabel)
        val intent = shortcut.intent!!
        assertEquals(context.packageName, intent.component?.packageName)
        assertEquals(MainActivity::class.java.name, intent.component?.className)
        assertEquals(Routes.SCANNER, intent.getStringExtra(Routes.EXTRA))
    }

    @Test fun tileReceiverAndApplicationAreDeclared() {
        val packages = context.packageManager
        val tile = packages.queryIntentServices(Intent(TileService.ACTION_QS_TILE).setPackage(context.packageName), 0).single()
        assertEquals(ScanTileService::class.java.name, tile.serviceInfo.name)
        val receiver = packages.queryBroadcastReceivers(Intent(Telephony.Sms.Intents.SMS_RECEIVED_ACTION).setPackage(context.packageName), 0).single()
        assertEquals(SmsReceiver::class.java.name, receiver.activityInfo.name)
        assertTrue(context.applicationContext is NeoTransferApplication)
        val route = routeIntent(context, Routes.SCANNER)
        assertEquals(Routes.SCANNER, route.getStringExtra(Routes.EXTRA))
        assertEquals(MainActivity::class.java.name, route.component?.className)
    }

    @Test fun legacyDataIsDeleted() {
        context.openOrCreateDatabase("wallet.db", Context.MODE_PRIVATE, null).close()
        context.getSharedPreferences("bank_vault", Context.MODE_PRIVATE).edit().putString("ciphertext", "x").commit()
        val session = File(context.noBackupFilesDir, "enzona-session.bin").apply { writeText("x") }
        LegacyData.clear(context)
        assertFalse(context.getDatabasePath("wallet.db").exists())
        assertFalse(File(context.dataDir, "shared_prefs/bank_vault.xml").exists())
        assertFalse(session.exists())
    }
}
