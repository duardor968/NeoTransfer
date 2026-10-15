package dev.duardo.neotransfer.scanner

import android.graphics.Point
import android.graphics.Rect
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.text.Text

internal fun Rect.toBox() = Box(left.toFloat(), top.toFloat(), right.toFloat(), bottom.toFloat())

private fun Point.toPt() = Pt(x.toFloat(), y.toFloat())

/** [map] converts ML Kit's coordinates into the caller's space. */
internal fun Barcode.sighting(map: (Pt) -> Pt = { it }): QrSighting? {
    val raw = rawValue?.takeIf(String::isNotEmpty) ?: return null
    val quad = cornerPoints?.takeIf { it.size == 4 }?.let { Quad(it[0].toPt(), it[1].toPt(), it[2].toPt(), it[3].toPt()) }
        ?: boundingBox?.let { Quad.of(it.toBox()) }
        ?: return null
    return QrSighting(raw, quad.map(map).upright())
}

internal fun Text.toLines(): List<OcrLine> = textBlocks.flatMap { it.lines }.mapNotNull { line ->
    line.elements.mapNotNull { element -> element.boundingBox?.let { OcrWord(element.text, it.toBox()) } }
        .takeIf { it.isNotEmpty() }
        ?.let(::OcrLine)
}

/** Maps a point from ML Kit's upright frame back to the unrotated buffer of [width]×[height]. */
internal fun fromUpright(point: Pt, width: Int, height: Int, rotation: Int): Pt = when (rotation) {
    90 -> Pt(point.y, height - point.x)
    180 -> Pt(width - point.x, height - point.y)
    270 -> Pt(width - point.y, point.x)
    else -> point
}
