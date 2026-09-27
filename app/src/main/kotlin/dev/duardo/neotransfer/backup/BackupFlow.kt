package dev.duardo.neotransfer.backup

import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.duardo.neotransfer.core.backup.BackupEnvelope
import dev.duardo.neotransfer.core.backup.BackupFailure
import dev.duardo.neotransfer.core.fuel.FuelBackupEnvelope
import dev.duardo.neotransfer.data.RestoreResult
import dev.duardo.neotransfer.data.WalletSnapshot
import dev.duardo.neotransfer.platform.AccessCredentials
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream

sealed interface BackupFlowState {
    data object Idle : BackupFlowState
    data class ExportPassword(val error: String? = null) : BackupFlowState
    data class ImportPassword(val error: String? = null) : BackupFlowState
    data class Working(val label: String) : BackupFlowState
    data class OwnPreview(val snapshot: WalletSnapshot, val hasCredentials: Boolean) : BackupFlowState
    data class TrmPreviewState(val source: TrmPreview, val mapping: TrmWalletMapping) : BackupFlowState
    data class Completed(val imported: Int, val conflicts: List<String>, val pendingAccesses: Int) : BackupFlowState
    data object Exported : BackupFlowState
    data class AccessesPending(val imported: Int, val conflicts: List<String>, val pendingAccesses: Int) : BackupFlowState
    data class Failed(val message: String) : BackupFlowState
}

/** Construct in Activity.onCreate before STARTED. SAF launchers are registered immediately. */
class BackupFlow(
    private val activity: ComponentActivity,
    private val snapshot: () -> WalletSnapshot,
    private val restore: (WalletSnapshot) -> RestoreResult,
    private val readPreferences: () -> BackupPreferences,
    private val applyPreferences: (BackupPreferences) -> Unit,
    private val decryptVault: ((ByteArray?) -> Unit) -> Unit,
    private val saveVault: (ByteArray, (Boolean) -> Unit) -> Unit,
    private val onRestored: () -> Unit,
    private val exportFuel: (WalletSnapshot) -> List<FuelBackupEnvelope>,
    private val restoreWithFuel: (WalletSnapshot, List<FuelBackupEnvelope>) -> RestoreResult,
) : AutoCloseable {
    var state by mutableStateOf<BackupFlowState>(BackupFlowState.Idle)
        private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var generation = 0
    private var closed = false
    private var pendingPassword: CharArray? = null
    private var pendingVaultWrite: ByteArray? = null
    private var pendingImport: ByteArray? = null
    private var decoded: DecodedBackup? = null
    private var trm: TrmWalletMapping? = null
    private var trmSource: TrmPreview? = null
    private var metadataImported: Pair<RestoreResult, Int>? = null
    private val exportStore = PendingEncryptedExport(File(activity.cacheDir, "neotransfer-pending-export.enc"),
        BackupEnvelope.maxEnvelopeBytes, 15 * 60 * 1000L)

    private val createDocument = activity.activityResultRegistry.register(
        "neotransfer.backup.create", activity,
        ActivityResultContracts.CreateDocument("application/octet-stream"),
    ) { uri -> writeExport(uri) }
    private val openBackup = activity.activityResultRegistry.register(
        "neotransfer.backup.open", activity, ActivityResultContracts.OpenDocument(),
    ) { uri -> readImport(uri, legacy = false) }
    private val openTrm = activity.activityResultRegistry.register(
        "neotransfer.trm.open", activity, ActivityResultContracts.OpenDocument(),
    ) { uri -> readImport(uri, legacy = true) }

    init { exportStore.expireIfNeeded() }

    fun beginExport() {
        generation++
        clearPending()
        exportStore.clear()
        state = BackupFlowState.ExportPassword()
    }

    fun beginImport() {
        generation++
        clearPending()
        exportStore.clear()
        state = BackupFlowState.Working("Seleccionar respaldo")
        openBackup.launch(arrayOf("*/*"))
    }

    fun beginTransfermovilImport() {
        generation++
        clearPending()
        exportStore.clear()
        state = BackupFlowState.Working("Seleccionar archivo Transfermóvil")
        openTrm.launch(arrayOf("*/*"))
    }

    /** Takes ownership of password and wipes it after encryption/decryption or cancellation. */
    fun submitPassword(password: CharArray) {
        if (password.isEmpty()) {
            password.fill('\u0000')
            state = when (state) {
                is BackupFlowState.ExportPassword -> BackupFlowState.ExportPassword("Introduce la contraseña")
                is BackupFlowState.ImportPassword -> BackupFlowState.ImportPassword("Introduce la contraseña")
                else -> state
            }
            return
        }
        when (state) {
            is BackupFlowState.ExportPassword -> export(password)
            is BackupFlowState.ImportPassword -> unlockImport(password)
            else -> password.fill('\u0000')
        }
    }

    private fun export(password: CharArray) {
        pendingPassword = password
        val request = ++generation
        state = BackupFlowState.Working("Preparando respaldo")
        decryptVault { vaultBytes ->
            if (closed || request != generation) { vaultBytes?.fill(0); return@decryptVault }
            if (vaultBytes == null) {
                pendingPassword?.fill('\u0000'); pendingPassword = null
                state = BackupFlowState.Failed("No se autorizó el acceso a las claves")
                return@decryptVault
            }
            scope.launch {
                try {
                    val encrypted = withContext(Dispatchers.IO) {
                        val value = snapshot()
                        val fuel = exportFuel(value)
                        try {
                            val payload = BackupPayload.encode(value, vaultBytes, readPreferences(), fuel)
                            try { BackupEnvelope.encrypt(payload, password) } finally { payload.fill(0) }
                        } finally { fuel.forEach(FuelBackupEnvelope::close) }
                    }
                    if (closed || request != generation) encrypted.fill(0) else {
                        try { withContext(Dispatchers.IO) { exportStore.save(encrypted) } }
                        finally { encrypted.fill(0) }
                        if (closed || request != generation) { exportStore.clear(); return@launch }
                        state = BackupFlowState.Working("Guardar respaldo")
                        createDocument.launch("NeoTransfer-backup.ntb")
                    }
                } catch (_: Exception) {
                    if (!closed && request == generation) state = BackupFlowState.Failed("No se pudo crear el respaldo")
                } finally {
                    vaultBytes.fill(0)
                    password.fill('\u0000')
                    if (pendingPassword === password) pendingPassword = null
                }
            }
        }
    }

    private fun writeExport(uri: Uri?) {
        if (uri == null) { exportStore.clear(); state = BackupFlowState.Idle; return }
        val request = generation
        state = BackupFlowState.Working("Guardando respaldo")
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    if (!exportStore.valid()) error("No hay respaldo cifrado pendiente")
                    activity.contentResolver.openOutputStream(uri, "w")?.use { output ->
                        exportStore.copyTo(output)
                        output.flush()
                    } ?: error("No se pudo abrir el destino")
                }
                if (!closed && request == generation) state = BackupFlowState.Exported
            } catch (_: Exception) {
                runCatching {
                    if (!DocumentsContract.deleteDocument(activity.contentResolver, uri)) {
                        activity.contentResolver.delete(uri, null, null)
                    }
                }
                if (!closed && request == generation) state = BackupFlowState.Failed("No se pudo guardar el respaldo")
            } finally { exportStore.clear() }
        }
    }

    private fun readImport(uri: Uri?, legacy: Boolean) {
        if (uri == null) { state = BackupFlowState.Idle; return }
        val request = ++generation
        state = BackupFlowState.Working("Leyendo archivo")
        scope.launch {
            try {
                val bytes = withContext(Dispatchers.IO) {
                    readBounded(uri, if (legacy) TrmImporter.maxInputBytes else BackupEnvelope.maxEnvelopeBytes)
                }
                if (closed || request != generation) { bytes.fill(0); return@launch }
                if (legacy) {
                    val result = try { withContext(Dispatchers.IO) {
                        TrmImporter.preview(bytes).let { it to TrmWalletMapper.map(it) }
                    } }
                        finally { bytes.fill(0) }
                    trmSource = result.first
                    trm = result.second
                    state = BackupFlowState.TrmPreviewState(result.first, result.second)
                } else {
                    pendingImport = bytes
                    state = BackupFlowState.ImportPassword()
                }
            } catch (error: Exception) {
                if (!closed && request == generation) state = BackupFlowState.Failed(
                    if (error is TrmFailure) error.message.orEmpty() else "No se pudo leer el archivo")
            }
        }
    }

    private fun unlockImport(password: CharArray) {
        val bytes = pendingImport ?: run { password.fill('\u0000'); return }
        pendingPassword = password
        val request = ++generation
        state = BackupFlowState.Working("Abriendo respaldo")
        scope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    val plaintext = BackupEnvelope.decrypt(bytes, password)
                    try { BackupPayload.decode(plaintext) } finally { plaintext.fill(0) }
                }
                if (closed || request != generation) result.close() else {
                    decoded?.close()
                    decoded = result
                    bytes.fill(0); pendingImport = null
                    state = BackupFlowState.OwnPreview(result.snapshot, result.credentials.isNotEmpty())
                }
            } catch (error: Exception) {
                if (!closed && request == generation) state = BackupFlowState.ImportPassword(
                    if (error is BackupFailure || error is BackupPayloadFailure) error.message.orEmpty()
                    else "No se pudo abrir el respaldo")
            } finally {
                password.fill('\u0000')
                if (pendingPassword === password) pendingPassword = null
            }
        }
    }

    fun confirmPreview() {
        when (state) {
            is BackupFlowState.OwnPreview, is BackupFlowState.AccessesPending -> confirmOwn()
            is BackupFlowState.TrmPreviewState -> confirmTrm()
            else -> Unit
        }
    }

    private fun confirmOwn() {
        val source = decoded ?: return
        val request = ++generation
        state = BackupFlowState.Working("Autorizando restauración")
        decryptVault { currentBytes ->
            if (closed || request != generation) { currentBytes?.fill(0); return@decryptVault }
            if (currentBytes == null) {
                state = BackupFlowState.OwnPreview(source.snapshot, source.credentials.isNotEmpty())
                return@decryptVault
            }
            scope.launch {
                try {
                    val work = withContext(Dispatchers.IO) {
                        val currentCredentials = if (currentBytes.isEmpty()) AccessCredentials.empty()
                            else AccessCredentials.decode(currentBytes)
                        try {
                            val imported = if (source.credentials.isEmpty()) AccessCredentials.empty()
                                else AccessCredentials.decode(source.credentials)
                            imported.use { importedCredentials ->
                                CredentialMerge.plan(snapshot(), currentCredentials, source.snapshot, importedCredentials).use { plan ->
                                    val result = restoreWithFuel(plan.snapshotForImport, source.fuelEnvelopes)
                                    RestoreWork(result, plan.encodeMerged(currentCredentials,
                                        result.registrationsToReassociate.toSet()), plan.pendingRegistrationIds.size)
                                }
                            }
                        } finally { currentCredentials.close() }
                    }
                    val (result, mergedBytes, pendingCount) = work
                    metadataImported = result to pendingCount
                    if (closed || request != generation) {
                        mergedBytes?.fill(0)
                        if (!closed) {
                            state = BackupFlowState.AccessesPending(result.importedEntries, result.conflicts, pendingCount)
                            onRestored()
                        }
                        return@launch
                    }
                    if (mergedBytes == null) finishOwn(result, pendingCount, true)
                    else {
                        pendingVaultWrite = mergedBytes
                        saveVault(mergedBytes) { saved ->
                            mergedBytes.fill(0)
                            if (pendingVaultWrite === mergedBytes) pendingVaultWrite = null
                            if (!closed && request == generation) finishOwn(result, pendingCount, saved)
                        }
                    }
                } catch (_: Exception) {
                    if (!closed && request == generation) state = BackupFlowState.Failed("No se pudo restaurar el respaldo")
                } finally { currentBytes.fill(0) }
            }
        }
    }

    private data class RestoreWork(val result: RestoreResult, val merged: ByteArray?, val pendingCount: Int)

    private fun finishOwn(result: RestoreResult, pendingCount: Int, credentialsSaved: Boolean) {
        if (credentialsSaved) {
            decoded?.let { applyPreferences(it.preferences) }
            decoded?.close(); decoded = null
            state = BackupFlowState.Completed(result.importedEntries, result.conflicts, pendingCount)
        } else {
            state = BackupFlowState.AccessesPending(result.importedEntries, result.conflicts, pendingCount)
        }
        onRestored()
    }

    private fun confirmTrm() {
        val mapping = trm ?: return
        val request = ++generation
        state = BackupFlowState.Working("Importando datos")
        scope.launch {
            try {
                val result = withContext(Dispatchers.IO) { restore(mapping.snapshot) }
                if (!closed && request == generation) {
                    state = BackupFlowState.Completed(result.importedEntries, result.conflicts, result.registrationsToReassociate.size)
                    onRestored()
                }
            } catch (_: Exception) {
                if (!closed && request == generation) state = BackupFlowState.Failed("No se pudo importar el archivo")
            }
        }
    }

    private fun readBounded(uri: Uri, limit: Int): ByteArray =
        activity.contentResolver.openInputStream(uri)?.use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            try {
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    if (output.size() + read > limit) throw IllegalArgumentException("El archivo supera el tamaño permitido")
                    output.write(buffer, 0, read)
                }
                require(output.size() > 0) { "El archivo está vacío" }
                output.toByteArray()
            } finally {
                buffer.fill(0)
            }
        } ?: error("No se pudo abrir el archivo")

    fun cancel() {
        generation++
        clearPending()
        exportStore.clear()
        state = BackupFlowState.Idle
    }

    /** Call from Activity.onStop; ciphertext awaiting SAF remains safe, plaintext does not. */
    fun onStop() {
        val sensitive = pendingPassword != null || pendingVaultWrite != null || decoded != null || trm != null
        if (!sensitive) return
        generation++
        val imported = metadataImported
        val alreadyPartial = state is BackupFlowState.AccessesPending
        clearPending()
        imported?.let { (result, count) ->
            state = BackupFlowState.AccessesPending(result.importedEntries, result.conflicts, count)
            if (!alreadyPartial) onRestored()
        } ?: run { state = BackupFlowState.Idle }
    }

    fun onLock() {
        // A completed, encrypted export may remain while the person chooses its SAF destination.
        if (exportStore.valid() &&
            pendingPassword == null && pendingVaultWrite == null && decoded == null && trm == null) return
        cancel()
    }

    private fun clearPending() {
        pendingPassword?.fill('\u0000'); pendingPassword = null
        pendingVaultWrite?.fill(0); pendingVaultWrite = null
        pendingImport?.fill(0); pendingImport = null
        decoded?.close(); decoded = null
        trm = null
        trmSource = null
        metadataImported = null
    }

    override fun close() {
        if (closed) return
        closed = true
        generation++
        clearPending()
        scope.cancel()
        createDocument.unregister(); openBackup.unregister(); openTrm.unregister()
    }
}

/** Ciphertext only. A new Activity instance can finish a pending CreateDocument result. */
internal class PendingEncryptedExport(
    private val file: File,
    private val maxBytes: Int,
    private val expiresAfterMs: Long,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    fun save(encrypted: ByteArray) {
        require(encrypted.isNotEmpty() && encrypted.size <= maxBytes)
        try {
            FileOutputStream(file).use { stream ->
                stream.write(encrypted)
                stream.fd.sync()
            }
            file.setLastModified(clock())
        } catch (error: Exception) {
            clear()
            throw error
        }
    }

    fun valid(): Boolean = file.isFile && file.length() in 1..maxBytes.toLong() &&
        clock() - file.lastModified() in 0L..expiresAfterMs

    fun copyTo(output: OutputStream) {
        check(valid()) { "No hay respaldo cifrado pendiente" }
        file.inputStream().use { input ->
            var total = 0
            val buffer = ByteArray(8192)
            try {
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    total += read
                    check(total <= maxBytes) { "El respaldo supera el tamaño permitido" }
                    output.write(buffer, 0, read)
                }
            } finally { buffer.fill(0) }
        }
    }

    fun expireIfNeeded() { if (file.isFile && !valid()) clear() }
    fun clear() { file.delete() }
}
