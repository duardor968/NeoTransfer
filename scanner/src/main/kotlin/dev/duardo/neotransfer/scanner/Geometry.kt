package dev.duardo.neotransfer.scanner

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/** Plain geometry so the detection logic runs on the JVM without Android classes. */
internal data class Pt(val x: Float, val y: Float) {
    operator fun plus(other: Pt) = Pt(x + other.x, y + other.y)
    operator fun minus(other: Pt) = Pt(x - other.x, y - other.y)
    operator fun times(factor: Float) = Pt(x * factor, y * factor)
    fun length() = hypot(x, y)
    fun unit(): Pt = length().let { if (it == 0f) this else Pt(x / it, y / it) }
}

internal data class Box(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width get() = right - left
    val height get() = bottom - top
    val center get() = Pt((left + right) / 2f, (top + bottom) / 2f)

    infix fun union(other: Box) =
        Box(min(left, other.left), min(top, other.top), max(right, other.right), max(bottom, other.bottom))

    fun iou(other: Box): Float {
        val w = min(right, other.right) - max(left, other.left)
        val h = min(bottom, other.bottom) - max(top, other.top)
        if (w <= 0f || h <= 0f) return 0f
        val shared = w * h
        return shared / (width * height + other.width * other.height - shared)
    }

    fun lerp(to: Box, t: Float) =
        Box(lerp(left, to.left, t), lerp(top, to.top, t), lerp(right, to.right, t), lerp(bottom, to.bottom, t))
}

/** Corners clockwise on screen, starting at the visual top-left. */
internal data class Quad(val tl: Pt, val tr: Pt, val br: Pt, val bl: Pt) {
    val corners get() = listOf(tl, tr, br, bl)

    fun map(transform: (Pt) -> Pt) = Quad(transform(tl), transform(tr), transform(br), transform(bl))

    fun bounds() = corners.let { c ->
        Box(c.minOf { it.x }, c.minOf { it.y }, c.maxOf { it.x }, c.maxOf { it.y })
    }

    fun area(): Float {
        val c = corners
        var sum = 0f
        for (i in c.indices) {
            val a = c[i]
            val b = c[(i + 1) % c.size]
            sum += a.x * b.y - b.x * a.y
        }
        return abs(sum) / 2f
    }

    /** Moves every corner [distance] away from the centre, so a frame can sit around the code. */
    fun outset(distance: Float): Quad {
        val center = corners.reduce(Pt::plus) * 0.25f
        return map { it + (it - center).unit() * distance }
    }

    /** Reorders arbitrary corners (rotated QR) so the frame morphs without twisting. */
    fun upright(): Quad {
        val center = corners.reduce(Pt::plus) * 0.25f
        val sorted = corners.sortedBy { atan2(it.y - center.y, it.x - center.x) }
        val start = sorted.indices.minBy { sorted[it].x + sorted[it].y }
        val c = List(4) { sorted[(start + it) % 4] }
        return Quad(c[0], c[1], c[2], c[3])
    }

    companion object {
        fun of(box: Box) = Quad(Pt(box.left, box.top), Pt(box.right, box.top), Pt(box.right, box.bottom), Pt(box.left, box.bottom))
    }
}

/** Integer region inside an image. */
internal data class Region(val left: Int, val top: Int, val width: Int, val height: Int)

internal fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t
