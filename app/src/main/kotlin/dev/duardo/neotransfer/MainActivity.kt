package dev.duardo.neotransfer

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.ContactsContract
import android.provider.OpenableColumns
import android.provider.Settings
import android.view.View
import android.view.ViewTreeObserver
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import dev.duardo.neotransfer.app.AppModel
import dev.duardo.neotransfer.app.Host
import dev.duardo.neotransfer.app.NeoApp
import dev.duardo.neotransfer.platform.BiometricGate
import dev.duardo.neotransfer.platform.BiometricResult
import kotlinx.coroutines.CompletableDeferred
import javax.crypto.Cipher

/** The only activity. It hosts [NeoApp] and does what only an activity can: prompts, pickers and other apps. */
class MainActivity : ComponentActivity(), Host {
    private val model: AppModel by viewModels()
    private val biometrics = BiometricGate(this)

    private var answer: CompletableDeferred<Any?>? = null
    private val gatePermissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { model.onPermissionsResult() }
    private val onePermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { answer?.complete(it) }
    private val createDocument = registerForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { answer?.complete(it) }
    private val openDocument = registerForActivityResult(ActivityResultContracts.OpenDocument()) { answer?.complete(it) }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        model.host = this
        if (savedInstanceState == null) model.open(intent)
        // The system splash stays until the stored state is in memory, so the first frame is already the right screen.
        val content = findViewById<View>(android.R.id.content)
        content.viewTreeObserver.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                if (!model.book.value.domain.loaded) return false
                content.viewTreeObserver.removeOnPreDrawListener(this)
                return true
            }
        })
        setContent { NeoApp(model) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        model.open(intent)
    }

    override fun onStart() {
        super.onStart()
        model.host = this
        model.onStart()
    }

    override fun onResume() {
        super.onResume()
        model.onResume()
    }

    override fun onDestroy() {
        if (model.host === this) model.host = null
        super.onDestroy()
    }

    // ---- Host ----

    override suspend fun biometric(title: String, subtitle: String?, cipher: Cipher?): BiometricResult = biometrics.prompt(title, subtitle, cipher)

    @Suppress("UNCHECKED_CAST")
    private suspend fun <T> ask(launch: () -> Unit): T {
        answer?.cancel()
        val pending = CompletableDeferred<Any?>()
        answer = pending
        launch()
        return pending.await() as T
    }

    override suspend fun createDocument(name: String): Uri? = ask { createDocument.launch(name) }
    override suspend fun openDocument(): Uri? = ask { openDocument.launch(arrayOf("*/*")) }
    override suspend fun requestPermission(permission: String): Boolean = ask { onePermission.launch(permission) }

    override fun displayName(uri: Uri): String? =
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null }

    override fun requestPermissions(permissions: List<String>) = gatePermissions.launch(permissions.toTypedArray())
    override fun shouldExplain(permission: String): Boolean = shouldShowRequestPermissionRationale(permission)

    override fun share(text: String) {
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), null))
    }

    override fun copy(text: String) {
        getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("NeoTransfer", text))
    }

    override fun open(url: String) = start(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    /** The Contacts editor; it comes back here once saved. */
    override fun openContact(key: String) = start(
        Intent(Intent.ACTION_EDIT, Uri.withAppendedPath(ContactsContract.Contacts.CONTENT_LOOKUP_URI, Uri.encode(key)))
            .putExtra("finishActivityOnSaveCompleted", true),
    )
    override fun openAppSettings() = start(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
    override fun openNotificationSettings() = start(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName))

    private fun start(intent: Intent) {
        try {
            startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            model.message("No hay ninguna app para abrirlo")
        }
    }
}
