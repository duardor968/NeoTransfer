package dev.duardo.neotransfer.platform

import android.content.Intent
import android.net.Uri

enum class TelecomActionKind { SMS_INFO, DIALER }

data class TelecomAction(
    val id: String,
    val title: String,
    val kind: TelecomActionKind,
    val destination: String,
    val body: String? = null,
)

/**
 * APK 1.260416: BktkqFBOIQ cases 0–3 and s9lZwtvq9Dl cases 0–4.
 * Opening an external composer/dialer does not establish that an SMS was sent or a call was placed.
 */
object TelecomActions {
    val all: List<TelecomAction> = listOf(
        TelecomAction("cubacel.info.offers", "Ofertas ETECSA", TelecomActionKind.SMS_INFO, "2266", "OFERTA"),
        TelecomAction("cubacel.info.tariffs", "Tarifas ETECSA", TelecomActionKind.SMS_INFO, "2266", "TARIFA"),
        TelecomAction("cubacel.info.plans", "Oferta Planes", TelecomActionKind.SMS_INFO, "2266", "PLANES"),
        TelecomAction("cubacel.info.friends", "Ofertas Amigos", TelecomActionKind.SMS_INFO, "2266", "AMIGOS"),
        TelecomAction("cubacel.emergency.ambulance", "Ambulancia", TelecomActionKind.DIALER, "104"),
        TelecomAction("cubacel.emergency.fire", "Bomberos", TelecomActionKind.DIALER, "105"),
        TelecomAction("cubacel.emergency.police", "PNR", TelecomActionKind.DIALER, "106"),
        TelecomAction("cubacel.emergency.maritime", "Salvamento marítimo", TelecomActionKind.DIALER, "107"),
        TelecomAction("cubacel.emergency.drugs", "Antidrogas", TelecomActionKind.DIALER, "103"),
    )

    fun find(id: String): TelecomAction? = all.firstOrNull { it.id == id }

    /** Composer fallback after SEND_SMS is denied; the caller owns review and ActivityNotFoundException. */
    fun intentFor(id: String): Intent? = find(id)?.let { action ->
        when (action.kind) {
            TelecomActionKind.SMS_INFO -> Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:${action.destination}"))
                .putExtra("sms_body", requireNotNull(action.body))
            TelecomActionKind.DIALER -> Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", action.destination, null))
        }
    }
}
