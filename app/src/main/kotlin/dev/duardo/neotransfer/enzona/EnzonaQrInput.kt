package dev.duardo.neotransfer.enzona

/** Only families demonstrated by the provider scanner are eligible for remote resolution. */
internal fun isEnzonaQrCandidate(value: String): Boolean = value.length in 3..16_384 &&
    listOf("01", "05", "07").any(value::startsWith) && value.none(Char::isISOControl)
