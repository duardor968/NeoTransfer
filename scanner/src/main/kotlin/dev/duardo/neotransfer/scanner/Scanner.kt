package dev.duardo.neotransfer.scanner

/** What the scanner looks for. */
enum class ScanMode { QR, NUMBERS, ANY }

/** A scan the person can act on. Number digits are plain: 16 for a card, 8 for a Cuban mobile (no `53`). */
sealed interface ScanResult {
    data class Qr(val raw: String) : ScanResult
    data class CardNumber(val digits: String) : ScanResult
    data class PhoneNumber(val digits: String) : ScanResult
}

/** The host decides whether a QR is useful; the scanner knows no payment formats. */
sealed interface QrVerdict {
    data object Accepted : QrVerdict
    /** [reason] is shown over the camera as is, so keep it short ("Este QR no es de pago"). */
    data class Rejected(val reason: String) : QrVerdict
}

/**
 * What an image contains. [results] holds accepted QR codes and number candidates; numbers still need the
 * person's confirmation. [rejection] carries the host's reason when the image only had rejected QR codes.
 */
data class ImageScan(val results: List<ScanResult>, val rejection: String?)

internal val ScanMode.readsQr get() = this != ScanMode.NUMBERS
internal val ScanMode.readsNumbers get() = this != ScanMode.QR
