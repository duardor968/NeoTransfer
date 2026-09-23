package dev.duardo.neotransfer

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.biometrics.BiometricManager
import android.os.Bundle
import android.os.CancellationSignal
import android.provider.ContactsContract
import android.net.Uri
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import dev.duardo.neotransfer.core.Bank
import dev.duardo.neotransfer.platform.BiometricGate

class MainActivity : ComponentActivity() {
    private val controller get() = (application as NeoTransferApplication).controller
    private var biometric: CancellationSignal? = null
    private var biometricActive by mutableStateOf(false)
    private var biometricCleanup: (() -> Unit)? = null
    private var permissionsVersion by mutableIntStateOf(0)
    private var selectedContact by mutableStateOf<PickedContact?>(null)
    private var screenHasSecrets = true
    private var permissionRequestActive = false
    private var incomingPayment by mutableStateOf<String?>(null)
    private val requestPermissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        permissionRequestActive = false
        permissionsVersion++; controller.readSims(); controller.reloadInbox()
    }
    private val pickContact = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val uri = result.data?.data ?: return@registerForActivityResult
        runCatching {
            contentResolver.query(uri, arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val digits = cursor.getString(1).filter { it in '0'..'9' }.let {
                        if (it.length == 10 && it.startsWith("53")) it.drop(2) else it
                    }
                    if (digits.length == 8) selectedContact = PickedContact(cursor.getString(0), digits)
                    else controller.notice = "El contacto no tiene un móvil cubano válido"
                }
            }
        }.onFailure { controller.notice = "No se pudo leer el contacto" }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        setRecentsScreenshotEnabled(false)
        controller.readSims()
        if (intent.action == Intent.ACTION_VIEW) incomingPayment = intent.dataString
        setContent {
            permissionsVersion // Recheck after the system permission dialog.
            val allPermissions = BANK_PERMISSIONS.all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
            NeoTransferApp(
                state = AppUiState(controller.unlocked, controller.vault.hasCredentials(), controller.bank,
                    controller.subscription, controller.sims, controller.configuredBanks, controller.busy || biometricActive,
                    controller.accounts, controller.balanceAt, controller.history, controller.pending, controller.confirmed,
                    controller.recipients, controller.notice, allPermissions,
                    checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED, selectedContact, controller.uncertain),
                actions = UiActions(
                    unlock = { unlock() }, enroll = { bank, pin, done -> enroll(bank, pin, done) },
                    permissions = { askPermissions(BANK_PERMISSIONS) },
                    cameraPermission = { askPermissions(arrayOf(Manifest.permission.CAMERA)) },
                    selectBank = controller::selectBank, selectSim = controller::selectSim,
                    balance = controller::queryBalance, refresh = controller::reloadInbox,
                    pay = { authorizePayment(it) }, saveRecipient = controller::saveRecipient,
                    resolvePending = controller::acknowledgePending, dismissNotice = { controller.notice = null },
                    pickContact = {
                        pickContact.launch(Intent(Intent.ACTION_PICK, ContactsContract.CommonDataKinds.Phone.CONTENT_URI))
                    }, clearContact = { selectedContact = null }, lock = controller::lock,
                    resolveUncertain = controller::resolveUncertain,
                    resetCredentials = {
                        controller.lock()
                        runCatching { controller.vault.reset() }.onFailure { controller.notice = "No se pudo restablecer el acceso" }
                        permissionsVersion++
                    },
                    protectScreen = { screenHasSecrets = it; updateScreenProtection() },
                    disconnectBank = controller::disconnectBank,
                    share = { text -> startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"; putExtra(Intent.EXTRA_TEXT, text)
                    }, "Compartir comprobante")) },
                ),
                incomingPayment = incomingPayment,
                consumeIncomingPayment = { incomingPayment = null },
            )
        }
        val onboarding = getSharedPreferences("permissions", MODE_PRIVATE)
        if (onboarding.getInt("onboarding", 0) < 2) {
            onboarding.edit().putInt("onboarding", 2).apply()
            window.decorView.post { askPermissions(ALL_PERMISSIONS, initial = true) }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent); setIntent(intent)
        if (intent.action == Intent.ACTION_VIEW) incomingPayment = intent.dataString
    }

    private fun askPermissions(requested: Array<String>, initial: Boolean = false) {
        if (permissionRequestActive) return
        val missing = requested.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) return
        val prefs = getSharedPreferences("permissions", MODE_PRIVATE)
        val asked = prefs.getStringSet("asked", emptySet()).orEmpty()
        if (!initial && missing.any { it in asked && !shouldShowRequestPermissionRationale(it) }) {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
            return
        }
        prefs.edit().putStringSet("asked", asked + missing).apply()
        permissionRequestActive = true
        requestPermissions.launch(missing.toTypedArray())
    }

    private fun updateScreenProtection() {
        val secure = screenHasSecrets || biometricActive || !controller.unlocked
        val currentlySecure = window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0
        if (secure != currentlySecure) {
            if (secure) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
            else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }

    override fun onResume() { super.onResume(); permissionsVersion++; controller.readSims() }

    private fun gate(title: String, subtitle: String, encrypt: Boolean = false, cleanup: () -> Unit = {}, success: (javax.crypto.Cipher) -> Unit) {
        if (biometricActive) { cleanup(); return }
        val gate = BiometricGate(this)
        if (gate.availability() != BiometricManager.BIOMETRIC_SUCCESS) {
            controller.notice = "Configura una huella o biometría fuerte en los ajustes del teléfono"
            cleanup()
            return
        }
        try {
            val cipher = if (encrypt) controller.vault.prepareEncryption() else controller.vault.prepareDecryption()
            biometricActive = true
            updateScreenProtection()
            biometricCleanup = cleanup
            biometric = gate.authenticate(cipher, title, subtitle,
                onSuccess = {
                    biometricActive = false; biometric = null
                    try { runCatching { success(it) }.onFailure { controller.notice = "No se pudo abrir el acceso guardado" } }
                    finally { cleanup(); biometricCleanup = null; updateScreenProtection() }
                }, onCancelled = { cleanup(); biometricCleanup = null; biometricActive = false; biometric = null; updateScreenProtection() },
                onError = { cleanup(); biometricCleanup = null; biometricActive = false; biometric = null; updateScreenProtection(); controller.notice = it })
        } catch (_: Exception) {
            biometricActive = false
            cleanup(); biometricCleanup = null
            updateScreenProtection()
            controller.notice = "No se pudo abrir el acceso guardado. La biometría del teléfono puede haber cambiado."
        }
    }

    private fun unlock() = gate("Abrir NeoTransfer", "Accede a tus cuentas") { cipher ->
        controller.unlock(controller.vault.finishDecryption(cipher))
    }

    private fun enroll(bank: Bank, pin: CharArray, done: () -> Unit) {
        val bytes = try { controller.enrollmentBytes(bank, pin) } finally { pin.fill('\u0000') }
        gate("Guardar acceso", bank.name, encrypt = true, cleanup = { bytes.fill(0) }) { cipher ->
            try {
                controller.vault.finishEncryption(cipher, bytes)
                controller.unlock(bytes)
                controller.selectBank(bank)
                done()
            } finally { bytes.fill(0) }
        }
    }

    private fun authorizePayment(action: MoneyAction) {
        if (!controller.unlocked || controller.pending != null || controller.busy) return
        val subtitle = if (action.kind == ActionKind.ELECTRICITY ||
            (action.kind == ActionKind.TELEPHONE && action.amount.amount.signum() == 0))
            "${action.bank.name} · Factura completa · ${action.destination}"
        else "${action.amount.amount.toPlainString()} ${action.amount.currency} · ${action.destination}"
        gate("Autorizar ${action.kind.label.lowercase()}", subtitle) { cipher ->
            val pin = controller.pinFrom(controller.vault.finishDecryption(cipher), action.bank)
            controller.submit(action, pin)
        }
    }

    override fun onStop() {
        super.onStop()
        biometric?.cancel(); biometric = null; biometricActive = false
        biometricCleanup?.invoke(); biometricCleanup = null
        controller.lock()
        updateScreenProtection()
    }

    private companion object {
        val BANK_PERMISSIONS = arrayOf(Manifest.permission.CALL_PHONE, Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.READ_SMS, Manifest.permission.RECEIVE_SMS)
        val ALL_PERMISSIONS = BANK_PERMISSIONS + Manifest.permission.CAMERA
    }
}
