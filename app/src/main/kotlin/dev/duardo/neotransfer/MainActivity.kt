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
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import dev.duardo.neotransfer.core.Bank
import dev.duardo.neotransfer.core.ProviderIdentity
import dev.duardo.neotransfer.core.OperationEffect
import dev.duardo.neotransfer.core.ServiceRequest
import dev.duardo.neotransfer.core.QrPayment
import dev.duardo.neotransfer.platform.AccessCredentials
import dev.duardo.neotransfer.platform.BiometricGate
import dev.duardo.neotransfer.platform.TransferNotifications
import dev.duardo.neotransfer.platform.ContactImporter
import dev.duardo.neotransfer.platform.TelecomSmsController
import dev.duardo.neotransfer.fuel.FuelController
import dev.duardo.neotransfer.backup.BackupFlow
import dev.duardo.neotransfer.backup.BackupPreferences
import dev.duardo.neotransfer.backup.BackupTheme
import dev.duardo.neotransfer.backup.BackupUi

class MainActivity : ComponentActivity() {
    private val neoApplication get() = application as NeoTransferApplication
    private val controller get() = neoApplication.controller
    private var biometric: CancellationSignal? = null
    private var biometricActive by mutableStateOf(false)
    private var accessRecoveryAvailable by mutableStateOf(false)
    private var biometricCleanup: (() -> Unit)? = null
    private var permissionsVersion by mutableIntStateOf(0)
    private var selectedContact by mutableStateOf<PickedContact?>(null)
    private var screenHasSecrets = true
    private var permissionRequestActive = false
    private var initialPermissionCheckPending = true
    private var resumed = false
    private var incomingPayment by mutableStateOf<String?>(null)
    private var incomingReceiptId by mutableStateOf<String?>(null)
    private val transferNotifications by lazy { TransferNotifications(this) }
    private val telecomSms by lazy { TelecomSmsController.android(this) }
    private val fuel by lazy { FuelController(neoApplication.fuelSecrets,
        readEnvelope = { id, revision ->
            val coupon = neoApplication.wallet.snapshot.fuelCoupons.singleOrNull { it.id == id && it.secretRevision == revision }
            if (coupon == null) null else withContext(Dispatchers.IO) {
                neoApplication.wallet.repository.protectedFuelEnvelopes(listOf(coupon)).singleOrNull()
            }
        }, currentCoupon = { id -> neoApplication.wallet.snapshot.fuelCoupons.singleOrNull { it.id == id } }) }
    private lateinit var backupFlow: BackupFlow
    private val requestPermissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        permissionRequestActive = false
        permissionsVersion++; controller.readSims(); controller.reloadInbox()
        window.decorView.post(::maybeUnlockAutomatically)
    }
    private val pickContact = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val uri = result.data?.data ?: return@registerForActivityResult
        runCatching {
            contentResolver.query(uri, arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER, ContactsContract.CommonDataKinds.Phone.LOOKUP_KEY), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val digits = cursor.getString(1).filter { it in '0'..'9' }.let {
                        if (it.length == 10 && it.startsWith("53")) it.drop(2) else it
                    }
                    if (digits.length == 8) {
                        selectedContact = PickedContact(cursor.getString(0), digits)
                    }
                    else controller.notice = "El contacto no tiene un móvil cubano válido"
                }
            }
        }.onFailure { controller.notice = "No se pudo leer el contacto" }
    }
    private val pickWholeContact = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val uri = result.data?.data ?: return@registerForActivityResult
        lifecycleScope.launch {
            try {
                val imported = withContext(Dispatchers.IO) { ContactImporter.importContact(this@MainActivity, uri) }
                if (!controller.unlocked) { controller.notice = "Desbloquea la cartera para importar el contacto"; return@launch }
                val existing = controller.walletSnapshot.contacts.singleOrNull { it.id == imported.id }
                controller.saveContact(if (existing == null) imported else existing.copy(name = imported.name,
                    phones = (imported.phones + existing.phones).distinctBy { it.number }))
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (failure: Exception) { controller.notice = failure.message ?: "No se pudo importar el contacto" }
        }
    }
    private val requestContactPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        permissionRequestActive = false
        permissionsVersion++
        if (granted && controller.unlocked) pickWholeContact.launch(Intent(Intent.ACTION_PICK, ContactsContract.Contacts.CONTENT_URI))
        else if (!granted) controller.notice = "Permite el acceso a Contactos para importar sus teléfonos"
    }

    private fun importContact() {
        if (!controller.unlocked || permissionRequestActive || biometricActive) return
        if (checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED) {
            pickWholeContact.launch(Intent(Intent.ACTION_PICK, ContactsContract.Contacts.CONTENT_URI))
        } else {
            val prefs = getSharedPreferences("permissions", MODE_PRIVATE)
            val asked = prefs.getStringSet("asked", emptySet()).orEmpty()
            if (Manifest.permission.READ_CONTACTS in asked && !shouldShowRequestPermissionRationale(Manifest.permission.READ_CONTACTS)) {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
            } else {
                prefs.edit().putStringSet("asked", asked + Manifest.permission.READ_CONTACTS).apply()
                permissionRequestActive = true
                requestContactPermission.launch(Manifest.permission.READ_CONTACTS)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        setRecentsScreenshotEnabled(false)
        controller.readSims()
        if (intent.action == Intent.ACTION_VIEW) incomingPayment = intent.dataString
        if (intent.action == TransferNotifications.ACTION_RECEIPT) incomingReceiptId = intent.getStringExtra(TransferNotifications.EXTRA_RECEIPT)
        controller.onSystemDial = ::sendSystemDial
        initializeBackupFlow()
        setContent {
            LaunchedEffect(controller.unlocked) { if (!controller.unlocked) fuel.clearSensitive() }
            permissionsVersion // Recheck after the system permission dialog.
            LaunchedEffect(neoApplication.wallet.ready) { if (neoApplication.wallet.ready) maybeUnlockAutomatically() }
            val allPermissions = BANK_PERMISSIONS.all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
            NeoTransferApp(
                state = AppUiState(controller.unlocked, controller.vault.hasStoredMaterial(), controller.bank,
                    controller.subscription, controller.sims, controller.configuredBanks, controller.busy || biometricActive || !neoApplication.wallet.ready,
                    controller.accounts, controller.balanceAt, controller.history, controller.pending, controller.confirmed,
                    controller.recipients, controller.notice ?: neoApplication.wallet.notice, allPermissions,
                    checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED, selectedContact, controller.uncertain,
                    wallet = controller.walletSnapshot, selectedProductId = controller.selectedProductId,
                    configuredRegistrationIds = controller.configuredRegistrationIds,
                    notificationsEnabled = transferNotifications.enabled, services = controller.services,
                    serviceResult = controller.serviceResult, accessRecoveryAvailable = accessRecoveryAvailable && !biometricActive),
                actions = UiActions(
                    unlock = { if (controller.vault.hasStoredMaterial()) unlock() else initializeLocalAccess() }, enroll = { bank, pin, done -> enroll(bank, pin, done) },
                    permissions = { askPermissions(BANK_PERMISSIONS) },
                    cameraPermission = { askPermissions(arrayOf(Manifest.permission.CAMERA)) },
                    selectBank = controller::selectBank, selectSim = controller::selectSim,
                    balance = controller::queryBalance, refresh = controller::reloadInbox,
                    pay = { authorizePayment(it) }, saveRecipient = controller::saveRecipient,
                    resolvePending = controller::acknowledgePending, dismissNotice = { controller.notice = null; neoApplication.wallet.notice = null },
                    pickContact = {
                        pickContact.launch(Intent(Intent.ACTION_PICK, ContactsContract.CommonDataKinds.Phone.CONTENT_URI))
                    }, clearContact = { selectedContact = null }, lock = ::lockAccess,
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
                    selectProduct = controller::selectProduct, selectRegistration = controller::selectRegistration,
                    balanceProduct = controller::queryBalance,
                    saveCard = controller::saveCard, deleteCard = controller::deleteCard,
                    saveAccount = controller::saveAccount, deleteAccount = controller::deleteAccount,
                    saveContact = controller::saveContact, deleteContact = controller::deleteContact,
                    saveService = { value -> neoApplication.wallet.transact(block = { putService(value) }) },
                    deleteService = { id -> neoApplication.wallet.transact(block = { deleteService(id) }) },
                    acknowledgeOperation = controller::acknowledgeOperation,
                    removeRegistration = ::removeAccess,
                    reassociateRegistration = ::reassociateAccess,
                    enrollProvider = ::enrollProvider,
                    setNotifications = { transferNotifications.enabled = it; permissionsVersion++ },
                    exportBackup = { backupFlow.beginExport() }, importBackup = { backupFlow.beginImport() },
                    importTransfermovil = { backupFlow.beginTransfermovilImport() },
                    importContacts = ::importContact,
                    runService = { authorizeService(it) },
                    runQrService = { request, qr -> authorizeService(request, qr) },
                    validateService = controller::validateService,
                    clearServiceResult = controller::clearServiceResult,
                    requestSmsPermission = { askPermissions(arrayOf(Manifest.permission.SEND_SMS)) },
                    renameFuel = { id, label -> neoApplication.wallet.transact(block = {
                        val coupon = snapshot().fuelCoupons.singleOrNull { it.id == id } ?: return@transact
                        updateFuelCouponPresentation(id, label, coupon.archived)
                    }) },
                    archiveFuel = { id, archived -> neoApplication.wallet.transact(block = {
                        val coupon = snapshot().fuelCoupons.singleOrNull { it.id == id } ?: return@transact
                        updateFuelCouponPresentation(id, coupon.label, archived)
                    }) },
                    authorizeFuel = { title, subtitle, result ->
                        confirmWithoutSavedAccess(title, subtitle, onFailure = { result(false) }) { result(controller.unlocked) }
                    },
                ),
                incomingPayment = incomingPayment,
                consumeIncomingPayment = { incomingPayment = null },
                incomingReceiptId = incomingReceiptId,
                consumeIncomingReceipt = { incomingReceiptId = null },
                telecomController = telecomSms,
                smsPermission = checkSelfPermission(Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED,
                fuelController = fuel,
            )
            BackupUi(backupFlow)
        }
        val onboarding = getSharedPreferences("permissions", MODE_PRIVATE)
        val needsOnboarding = onboarding.getInt("onboarding", 0) < 5
        if (needsOnboarding) onboarding.edit().putInt("onboarding", 5).apply()
        window.decorView.post {
            initialPermissionCheckPending = false
            if (needsOnboarding) {
                val asked = onboarding.getStringSet("asked", emptySet()).orEmpty()
                askPermissions(ALL_PERMISSIONS.filterNot { it in asked }.toTypedArray(), initial = true)
            }
            maybeUnlockAutomatically()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent); setIntent(intent)
        if (intent.action == Intent.ACTION_VIEW) incomingPayment = intent.dataString
        if (intent.action == TransferNotifications.ACTION_RECEIPT) incomingReceiptId = intent.getStringExtra(TransferNotifications.EXTRA_RECEIPT)
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
        val secure = screenHasSecrets || biometricActive
        val currentlySecure = window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0
        if (secure != currentlySecure) {
            if (secure) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
            else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }

    override fun onStart() {
        super.onStart()
        neoApplication.enterForeground()
        updateScreenProtection()
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        permissionsVersion++; controller.readSims()
        window.decorView.post(::maybeUnlockAutomatically)
    }

    override fun onPause() { resumed = false; super.onPause() }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) window.decorView.post(::maybeUnlockAutomatically)
    }

    private fun maybeUnlockAutomatically() {
        if (!resumed || isFinishing || isDestroyed || initialPermissionCheckPending ||
            permissionRequestActive || biometricActive || !neoApplication.wallet.ready) return
        if (getSystemService(android.app.KeyguardManager::class.java).isKeyguardLocked) return
        if (neoApplication.accessSession.claimAutomaticPrompt(controller.unlocked)) {
            if (!controller.vault.hasStoredMaterial()) initializeLocalAccess() else unlock()
        }
    }

    private fun gate(title: String, subtitle: String? = null, encrypt: Boolean = false, cleanup: () -> Unit = {},
                     onFailure: () -> Unit = {}, recoverAccessOnFailure: Boolean = false, success: (javax.crypto.Cipher) -> Unit) {
        if (biometricActive) { cleanup(); onFailure(); return }
        val gate = BiometricGate(this)
        if (gate.availability() != BiometricManager.BIOMETRIC_SUCCESS) {
            controller.notice = "Configura la biometría en los ajustes del teléfono"
            cleanup()
            onFailure()
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
                    try { runCatching { success(it) }.onFailure {
                        if (recoverAccessOnFailure) accessRecoveryAvailable = true
                        controller.notice = "No se pudo abrir el acceso guardado"; onFailure()
                    } }
                    finally { cleanup(); biometricCleanup = null; updateScreenProtection() }
                }, onCancelled = { cleanup(); biometricCleanup = null; biometricActive = false; biometric = null; updateScreenProtection(); onFailure() },
                onError = { cleanup(); biometricCleanup = null; biometricActive = false; biometric = null; updateScreenProtection(); controller.notice = it; onFailure() })
        } catch (_: Exception) {
            if (recoverAccessOnFailure) accessRecoveryAvailable = true
            biometricActive = false
            cleanup(); biometricCleanup = null
            updateScreenProtection()
            controller.notice = "No se pudo abrir el acceso guardado"
            onFailure()
        }
    }

    private fun initializeLocalAccess() {
        accessRecoveryAvailable = false
        neoApplication.accessSession.markManualPrompt()
        val bytes = AccessCredentials.empty().use { it.encode() }
        gate("Desbloquear", encrypt = true, cleanup = { bytes.fill(0) }) { cipher ->
            controller.vault.finishEncryption(cipher, bytes)
            controller.unlock(bytes)
        }
    }

    private fun confirmWithoutSavedAccess(title: String, subtitle: String? = null, onFailure: () -> Unit = {}, success: () -> Unit) {
        if (biometricActive || !resumed) { onFailure(); return }
        val nativeGate = BiometricGate(this)
        if (nativeGate.availability() != BiometricManager.BIOMETRIC_SUCCESS) {
            controller.notice = "Configura la biometría en los ajustes del teléfono"
            onFailure()
            return
        }
        biometricActive = true
        updateScreenProtection()
        var completed = false
        fun finish(approved: Boolean) {
            if (completed) return
            completed = true
            biometric = null; biometricActive = false; biometricCleanup = null
            updateScreenProtection()
            if (approved) runCatching(success).onFailure { controller.notice = "No se pudo autorizar la operación"; onFailure() }
            else onFailure()
        }
        biometricCleanup = { finish(false) }
        try {
            biometric = nativeGate.confirm(title, subtitle,
                onSuccess = { finish(true) }, onCancelled = { finish(false) },
                onError = { finish(false); controller.notice = it })
        } catch (_: Exception) {
            finish(false)
            controller.notice = "No se pudo abrir la autorización biométrica"
        }
    }

    private fun initializeBackupFlow() {
        backupFlow = BackupFlow(this,
            snapshot = { neoApplication.wallet.repository.snapshot() },
            restore = { neoApplication.wallet.repository.importSnapshot(it) },
            readPreferences = {
                val prefs = getSharedPreferences("appearance", MODE_PRIVATE)
                val theme = prefs.getString("theme", null)?.let { runCatching { BackupTheme.valueOf(it) }.getOrNull() }
                    ?: if (prefs.contains("dark")) if (prefs.getBoolean("dark", true)) BackupTheme.DARK else BackupTheme.LIGHT else BackupTheme.SYSTEM
                BackupPreferences(theme, transferNotifications.enabled)
            },
            applyPreferences = { preferences ->
                getSharedPreferences("appearance", MODE_PRIVATE).edit().putString("theme", preferences.theme.name).remove("dark").apply()
                transferNotifications.enabled = preferences.notificationsEnabled
                runOnUiThread { permissionsVersion++ }
            },
            decryptVault = { result ->
                if (!controller.vault.hasStoredMaterial()) confirmWithoutSavedAccess("Exportar respaldo", onFailure = { result(null) }) { result(ByteArray(0)) }
                else gate("Exportar respaldo", onFailure = { result(null) }) { cipher ->
                    result(controller.vault.finishDecryption(cipher))
                }
            },
            saveVault = { bytes, result -> saveProtectedAccess(bytes, result) },
            onRestored = { runOnUiThread { controller.syncSelection(); permissionsVersion++ } },
            exportFuel = { snapshot ->
                neoApplication.fuelSecrets.exportForBackup(snapshot.fuelCoupons,
                    neoApplication.wallet.repository.protectedFuelEnvelopes(snapshot.fuelCoupons))
            },
            restoreWithFuel = { snapshot, capsules ->
                val protected = neoApplication.fuelSecrets.protectAfterRestore(snapshot.fuelCoupons, capsules)
                neoApplication.wallet.repository.importSnapshot(snapshot, protected)
            },
        )
    }

    private fun saveProtectedAccess(bytes: ByteArray, result: (Boolean) -> Unit) {
        val owned = bytes.copyOf()
        gate("Restaurar accesos", encrypt = true,
            cleanup = { owned.fill(0) }, onFailure = { result(false) }) { cipher ->
            controller.vault.finishEncryption(cipher, owned)
            controller.unlock(owned.copyOf())
            result(true)
        }
    }

    private fun enrollProvider(identity: ProviderIdentity, pin: CharArray, done: () -> Unit) {
        val ownedPin = pin.copyOf()
        pin.fill('\u0000')
        neoApplication.wallet.reserveRegistration(identity, controller.subscription, onFailure = { ownedPin.fill('\u0000') }) { reserved ->
            if (isFinishing || isDestroyed || !lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) {
                ownedPin.fill('\u0000'); return@reserveRegistration
            }
            val registration = reserved.copy(enabled = true)
            val bytes = try { controller.enrollmentBytesProvider(identity, registration, ownedPin) }
            catch (_: Exception) { controller.notice = "Revisa la clave del acceso"; return@reserveRegistration }
            finally { ownedPin.fill('\u0000') }
            gate("Guardar acceso", identity.provider.name, encrypt = true, cleanup = { bytes.fill(0) }) { cipher ->
                controller.vault.finishEncryption(cipher, bytes)
                controller.unlock(bytes.copyOf())
                neoApplication.wallet.saveRegistration(registration.copy(credentialAlias = controller.credentialAliasFor(registration))) {
                    controller.accessSaved(registration.id)
                    controller.syncSelection()
                    done()
                }
            }
        }
    }

    private fun removeAccess(id: String) {
        val bytes = runCatching { controller.credentialsWithoutRegistration(id) }.getOrElse {
            controller.notice = "Revisa las operaciones pendientes antes de quitar el acceso"; return
        }
        gate("Quitar acceso", "El registro en el banco se conserva", encrypt = true, cleanup = { bytes.fill(0) }) { cipher ->
            controller.vault.finishEncryption(cipher, bytes)
            controller.unlock(bytes.copyOf())
            controller.removeRegistration(id)
        }
    }

    private fun reassociateAccess(id: String, subscriptionId: Int) {
        if (biometricActive || controller.busy || !controller.unlocked) return
        val registration = controller.walletSnapshot.registrations.singleOrNull { it.id == id } ?: return
        val sim = controller.sims.singleOrNull { it.id == subscriptionId } ?: return
        confirmWithoutSavedAccess("Vincular acceso", "${registration.label} · ${sim.label}") {
            runCatching { controller.reassociateRegistration(id, subscriptionId) }.onFailure {
                controller.notice = it.message ?: "No se pudo vincular la línea"
            }
        }
    }

    private fun authorizeService(request: ServiceRequest, originalQr: QrPayment? = null) {
        if (biometricActive || controller.busy) return
        val errors = controller.validateService(request)
        if (errors.isNotEmpty()) { controller.notice = errors.joinToString("\n") { it.message }; return }
        val spec = controller.services.singleOrNull { it.id == request.operationId } ?: return
        val executionContext = runCatching { controller.serviceContext(request).copy(originalQr = originalQr) }.getOrElse {
            controller.notice = it.message ?: "Selecciona un acceso compatible"; return
        }
        val contextErrors = controller.validateService(request, executionContext)
        if (contextErrors.isNotEmpty()) { controller.notice = contextErrors.joinToString("\n") { it.message }; return }
        if (spec.effect == OperationEffect.QUERY) {
            controller.runService(request, executionContext = executionContext)
            return
        }
        val registration = executionContext.registration
        if (registration == null || registration.credentialAlias == null || !spec.requiresSession && spec.fields.none { it.suppliedByAccess }) {
            confirmWithoutSavedAccess(spec.title) {
                controller.runService(request, executionContext = executionContext, approved = true)
            }
        } else gate(spec.title, registration.label.takeUnless { spec.title.contains(it, ignoreCase = true) }) { cipher ->
            val pin = controller.pinFrom(controller.vault.finishDecryption(cipher), registration)
            controller.runService(request, pin, executionContext, approved = true)
        }
    }

    private fun sendSystemDial(request: SystemDialRequest) {
        try {
            require(resumed && !isFinishing)
            if (checkSelfPermission(Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED ||
                checkSelfPermission(Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) {
                controller.notice = "Permite el acceso al teléfono y las llamadas para utilizar la línea seleccionada"
                request.complete(false)
                return
            }
            val telephony = getSystemService(android.telephony.TelephonyManager::class.java)
            val telecom = getSystemService(android.telecom.TelecomManager::class.java)
            val handles = telecom.callCapablePhoneAccounts.filter { handle ->
                telephony.createForPhoneAccountHandle(handle)?.subscriptionId == request.executionContext.subscriptionId
            }
            val handle = handles.singleOrNull() ?: throw IllegalStateException("No se pudo identificar la línea seleccionada")
            startActivity(Intent(Intent.ACTION_CALL, Uri.fromParts("tel", request.command.valueForTransport(), null)).apply {
                putExtra(android.telecom.TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, handle)
            })
            request.complete(true)
        } catch (_: SecurityException) {
            controller.notice = "El permiso de teléfono cambió. Revísalo antes de continuar."
            request.complete(false)
        } catch (_: Exception) {
            controller.notice = "No se pudo utilizar la línea seleccionada"
            request.complete(false)
        }
    }

    private fun lockAccess() {
        biometric?.cancel(); biometric = null; biometricActive = false
        biometricCleanup?.invoke(); biometricCleanup = null
        if (::backupFlow.isInitialized) backupFlow.onLock()
        fuel.clearSensitive()
        controller.lock()
        updateScreenProtection()
    }

    private fun unlock() {
        accessRecoveryAvailable = false
        neoApplication.accessSession.markManualPrompt()
        gate("Desbloquear", recoverAccessOnFailure = true) { cipher ->
            controller.unlock(controller.vault.finishDecryption(cipher))
        }
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
        val executionContext = runCatching { controller.moneyContext(action) }.getOrElse {
            controller.notice = it.message ?: "Selecciona el acceso de origen"; return
        }
        val registration = executionContext.registration ?: run { controller.notice = "Configura el acceso de origen"; return }
        val subtitle = if (action.kind == ActionKind.ELECTRICITY ||
            (action.kind == ActionKind.TELEPHONE && action.amount.amount.signum() == 0))
            "${action.bank.name} · Factura completa · ${action.destination}"
        else "${action.amount.amount.toPlainString()} ${action.amount.currency} · ${action.destination}"
        val title = when (action.kind) { ActionKind.TRANSFER -> "Transferir"; ActionKind.RECHARGE -> "Recargar"; else -> "Pagar" }
        gate(title, subtitle) { cipher ->
            val pin = controller.pinFrom(controller.vault.finishDecryption(cipher), registration)
            controller.submit(action, pin, executionContext)
        }
    }

    override fun onStop() {
        super.onStop()
        biometric?.cancel(); biometric = null; biometricActive = false
        biometricCleanup?.invoke(); biometricCleanup = null
        if (::backupFlow.isInitialized) backupFlow.onStop()
        fuel.clearSensitive()
        neoApplication.leaveForeground()
        updateScreenProtection()
    }

    override fun onDestroy() {
        if (::backupFlow.isInitialized) backupFlow.close()
        fuel.close()
        super.onDestroy()
    }

    private companion object {
        val BANK_PERMISSIONS = arrayOf(Manifest.permission.CALL_PHONE, Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.READ_SMS, Manifest.permission.RECEIVE_SMS)
        val ALL_PERMISSIONS = BANK_PERMISSIONS + arrayOf(Manifest.permission.CAMERA, Manifest.permission.POST_NOTIFICATIONS, Manifest.permission.READ_CONTACTS, Manifest.permission.SEND_SMS)
    }
}
