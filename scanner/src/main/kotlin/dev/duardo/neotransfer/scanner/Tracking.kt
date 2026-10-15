package dev.duardo.neotransfer.scanner

/** A decoded QR and its corners, in the coordinate space of whatever is on screen. */
internal data class QrSighting(val raw: String, val quad: Quad)

/**
 * Follows QR codes across frames. A code is classified only after [needed] consecutive sightings, a rejected
 * code is announced once while it stays in view, and a delivered code is ignored until it leaves the view.
 */
internal class QrFlow {
    sealed interface State {
        data object Idle : State
        data class Seeking(val raw: String, val quad: Quad, val hits: Int, val seenAt: Long) : State
        data class Accepted(val raw: String, val quad: Quad) : State
        data class Rejected(val raw: String, val quad: Quad, val reason: String, val seenAt: Long) : State
    }

    var state: State = State.Idle
        private set
    private var spent: String? = null
    private var spentSeenAt = 0L

    /** Returns the verdict when a code is confirmed in this frame. */
    fun onFrame(sightings: List<QrSighting>, now: Long, needed: Int, classify: (String) -> QrVerdict): QrVerdict? {
        val fresh = sightings.filter { sighting ->
            (sighting.raw != spent).also { if (!it) spentSeenAt = now }
        }
        if (spent != null && now - spentSeenAt > LEAVE_MS) spent = null
        val current = state
        val target = fresh.firstOrNull { it.raw == current.raw } ?: fresh.maxByOrNull { it.quad.area() }
        when (current) {
            is State.Accepted -> {
                if (target?.raw == current.raw) state = current.copy(quad = target.quad)
                return null
            }
            is State.Rejected -> if (target == null || target.raw == current.raw) {
                state = when {
                    target != null -> current.copy(quad = target.quad, seenAt = now)
                    now - current.seenAt > REJECTED_LINGER_MS -> State.Idle
                    else -> current
                }
                return null
            }
            is State.Seeking -> if (target == null) {
                if (now - current.seenAt > LOST_MS) state = State.Idle
                return null
            } else if (target.raw == current.raw && now - current.seenAt <= LOST_MS) {
                return advance(target, current.hits + 1, now, needed, classify)
            }
            State.Idle -> if (target == null) return null
        }
        return advance(target!!, 1, now, needed, classify)
    }

    private fun advance(target: QrSighting, hits: Int, now: Long, needed: Int, classify: (String) -> QrVerdict): QrVerdict? {
        if (hits < needed) {
            state = State.Seeking(target.raw, target.quad, hits, now)
            return null
        }
        val verdict = classify(target.raw)
        state = when (verdict) {
            QrVerdict.Accepted -> State.Accepted(target.raw, target.quad)
            is QrVerdict.Rejected -> State.Rejected(target.raw, target.quad, verdict.reason, now)
        }
        return verdict
    }

    /** The accepted code was handed to the host: keep scanning, but not that code until it leaves the view. */
    fun delivered(now: Long) {
        (state as? State.Accepted)?.let {
            spent = it.raw
            spentSeenAt = now
        }
        state = State.Idle
    }

    fun reset() {
        state = State.Idle
        spent = null
    }

    private val State.raw
        get() = when (this) {
            State.Idle -> null
            is State.Seeking -> raw
            is State.Accepted -> raw
            is State.Rejected -> raw
        }

    private companion object {
        const val LOST_MS = 600L
        const val REJECTED_LINGER_MS = 1_200L
        const val LEAVE_MS = 1_000L
    }
}

internal data class TrackedNumber(val kind: NumberKind, val digits: String, val box: Box, val visible: Boolean)

/**
 * Proposes a number only after it was read the same way in several frames (cards carry Luhn, so they need
 * fewer). Competing readings of the same spot keep the most repeated one.
 */
internal class NumberTracker {
    private class Track(val hit: NumberHit, var box: Box, var hits: Int, var seenAt: Long)

    private val tracks = LinkedHashMap<Pair<NumberKind, String>, Track>()

    fun update(hits: List<NumberHit>, now: Long): List<TrackedNumber> {
        for (hit in hits) {
            val track = tracks[hit.kind to hit.digits]
            if (track == null) {
                tracks[hit.kind to hit.digits] = Track(hit, hit.box, 1, now)
            } else {
                track.box = track.box.lerp(hit.box, 0.6f)
                track.hits++
                track.seenAt = now
            }
        }
        tracks.values.removeAll { now - it.seenAt > KEEP_MS }
        val stable = tracks.values.filter { it.hits >= needed(it.hit.kind) }
        return stable
            .filter { track ->
                stable.none { other ->
                    other !== track && other.hit.kind == track.hit.kind && other.hits > track.hits &&
                        other.box.iou(track.box) > 0.3f
                }
            }
            .map { TrackedNumber(it.hit.kind, it.hit.digits, it.box, now - it.seenAt <= VISIBLE_MS) }
    }

    fun clear() = tracks.clear()

    private fun needed(kind: NumberKind) = if (kind == NumberKind.CARD) 2 else 3

    private companion object {
        const val KEEP_MS = 2_500L
        const val VISIBLE_MS = 450L
    }
}
