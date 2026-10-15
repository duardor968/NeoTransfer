package dev.duardo.neotransfer.ui.common

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import dev.duardo.neotransfer.designsystem.format.formatCardNumber

/** `9204123456789012` shown as `9204 1234 5678 9012` while the field keeps digits only. */
val CardNumberTransformation = VisualTransformation { text ->
    val digits = text.text
    val out = formatCardNumber(digits)
    TransformedText(
        AnnotatedString(out),
        object : OffsetMapping {
            override fun originalToTransformed(offset: Int) = offset + (offset - 1).coerceAtLeast(0) / 4
            override fun transformedToOriginal(offset: Int) = (offset - offset / 5).coerceIn(0, digits.length)
        },
    )
}

/** `0135` shown as `01/35`. */
val ExpiryTransformation = VisualTransformation { text ->
    val d = text.text
    val out = if (d.length > 2) d.substring(0, 2) + "/" + d.substring(2) else d
    TransformedText(
        AnnotatedString(out),
        object : OffsetMapping {
            override fun originalToTransformed(offset: Int) = if (offset > 2) offset + 1 else offset
            override fun transformedToOriginal(offset: Int) = (if (offset > 2) offset - 1 else offset).coerceIn(0, d.length)
        },
    )
}

/** `53124578` shown as `5312 4578`, the way numbers are written everywhere else. */
val PhoneTransformation = VisualTransformation { text ->
    val d = text.text
    val out = if (d.length > 4) d.substring(0, 4) + " " + d.substring(4) else d
    TransformedText(
        AnnotatedString(out),
        object : OffsetMapping {
            override fun originalToTransformed(offset: Int) = if (offset > 4) offset + 1 else offset
            override fun transformedToOriginal(offset: Int) = (if (offset > 4) offset - 1 else offset).coerceIn(0, d.length)
        },
    )
}
