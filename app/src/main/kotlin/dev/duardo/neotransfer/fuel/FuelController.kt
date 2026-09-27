package dev.duardo.neotransfer.fuel

import dev.duardo.neotransfer.core.fuel.FuelCoupon
import dev.duardo.neotransfer.core.fuel.FuelCredential
import dev.duardo.neotransfer.core.fuel.ProtectedFuelEnvelope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

class FuelPresentation internal constructor(val couponId: String, val serial: String, val revision: String,
                                            private val credential: FuelCredential) : AutoCloseable {
    fun qrPayload(): String = credential.qrPayload()
    fun pinText(): String = credential.copyPinForDisplay().let { try { String(it) } finally { it.fill('\u0000') } }
    fun tokenText(): String = credential.copyTokenForDisplay().let { try { String(it) } finally { it.fill('\u0000') } }
    override fun close() = credential.close()
    override fun toString() = "FuelPresentation(couponId=$couponId)"
}

data class FuelUiState(val busy: Boolean = false, val presentation: FuelPresentation? = null, val error: String? = null)

/** Local presentation only. Purchasing and key changes stay in the common operation/review pipeline. */
class FuelController(
    private val secretStore: FuelSecretStore,
    private val readEnvelope: suspend (couponId: String, revision: String) -> ProtectedFuelEnvelope?,
    private val currentCoupon: (String) -> FuelCoupon?,
    mainDispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + mainDispatcher)
    private val mutableState = MutableStateFlow(FuelUiState())
    val state = mutableState.asStateFlow()
    private var generation = 0L
    private var closed = false
    private var revealing: FuelCoupon? = null

    fun showCoupon(coupon: FuelCoupon, authorize: (String, String, (Boolean) -> Unit) -> Unit) {
        if (closed) return
        clearSensitive()
        if (!sameCredential(coupon, currentCoupon(coupon.id))) {
            mutableState.value = FuelUiState(error = "Consulta de nuevo este cupón antes de mostrarlo.")
            return
        }
        revealing = coupon
        val attempt = generation
        val answered = AtomicBoolean(false)
        mutableState.value = FuelUiState(busy = true)
        authorize("Mostrar cupón", "Serie ${coupon.serial}") { approved ->
            if (!answered.compareAndSet(false, true)) return@authorize
            scope.launch {
                if (closed || attempt != generation) return@launch
                if (!approved) { clearSensitive(); return@launch }
                if (!sameCredential(coupon, currentCoupon(coupon.id))) {
                    clearSensitive()
                    mutableState.value = FuelUiState(error = "El cupón cambió. Vuelve a abrirlo.")
                    return@launch
                }
                var presentation: FuelPresentation? = null
                try {
                    withContext(ioDispatcher) {
                        val stored = readEnvelope(coupon.id, requireNotNull(coupon.secretRevision))
                            ?: error("No hay datos protegidos para el cupón")
                        secretStore.open(coupon, stored).use { envelope ->
                            presentation = FuelPresentation(coupon.id, coupon.serial, stored.revision,
                                FuelCredential.fromProviderEnvelope(coupon, envelope))
                        }
                    }
                    if (closed || attempt != generation) return@launch
                    if (!sameCredential(coupon, currentCoupon(coupon.id))) {
                        clearSensitive()
                        mutableState.value = FuelUiState(error = "El cupón cambió. Vuelve a abrirlo.")
                        return@launch
                    }
                    mutableState.value = FuelUiState(presentation = requireNotNull(presentation))
                    presentation = null
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    if (!closed && attempt == generation) mutableState.value = FuelUiState(
                        error = "No se pudo abrir el cupón. Consulta su estado o recupéralo de tu respaldo.")
                } finally { presentation?.close() }
            }
        }
    }

    /** Call when the metadata snapshot changes, and clearSensitive whenever the app locks or leaves this screen. */
    fun syncCoupons(coupons: List<FuelCoupon>) {
        revealing?.let { original ->
            if (!sameCredential(original, coupons.singleOrNull { it.id == original.id })) clearSensitive()
        }
    }

    fun clearSensitive() {
        generation++
        revealing = null
        mutableState.value.presentation?.close()
        mutableState.value = FuelUiState()
    }

    override fun close() { clearSensitive(); closed = true; scope.cancel() }

    private fun sameCredential(original: FuelCoupon, current: FuelCoupon?): Boolean =
        current != null && !current.ambiguous && !original.ambiguous && original.secretRevision != null &&
            current.id == original.id && current.serial == original.serial && current.secretRevision == original.secretRevision &&
            current.bankReference == original.bankReference && current.tmReference == original.tmReference
}
