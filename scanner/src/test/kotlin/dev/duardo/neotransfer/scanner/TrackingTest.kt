package dev.duardo.neotransfer.scanner

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun quad(x: Float, size: Float = 100f) = Quad.of(Box(x, 0f, x + size, size))
private fun seen(raw: String, x: Float = 0f) = QrSighting(raw, quad(x))

class TrackingTest {
    private val classify: (String) -> QrVerdict = { raw ->
        if (raw.startsWith("PAY")) QrVerdict.Accepted else QrVerdict.Rejected("Este QR no es de pago")
    }
    private val flow = QrFlow()

    private fun frame(now: Long, vararg sightings: QrSighting, needed: Int = 2) =
        flow.onFrame(sightings.toList(), now, needed, classify)

    @Test
    fun qrNeedsTwoFramesAndFollowsTheCode() {
        assertNull(frame(0, seen("PAY1")))
        assertIs<QrFlow.State.Seeking>(flow.state)
        assertEquals(QrVerdict.Accepted, frame(100, seen("PAY1", x = 10f)))
        assertEquals(QrFlow.State.Accepted("PAY1", quad(10f)), flow.state)
        assertNull(frame(200, seen("PAY1", x = 20f)), "accepted codes are announced once")
        assertEquals(QrFlow.State.Accepted("PAY1", quad(20f)), flow.state)
    }

    @Test
    fun aDifferentCodeOrALongGapRestartsTheCount() {
        frame(0, seen("PAY1"))
        assertNull(frame(100, seen("PAY2")))
        assertEquals("PAY2", (flow.state as QrFlow.State.Seeking).raw)
        assertNull(frame(1_000, seen("PAY2")), "the previous sighting is too old")
        assertEquals(QrVerdict.Accepted, frame(1_100, seen("PAY2")))
    }

    @Test
    fun lostCodeReturnsTheFrameToRest() {
        frame(0, seen("PAY1"))
        frame(300)
        assertIs<QrFlow.State.Seeking>(flow.state)
        frame(700)
        assertEquals(QrFlow.State.Idle, flow.state)
    }

    @Test
    fun rejectedCodeIsAnnouncedOnceAndKeepsScanning() {
        frame(0, seen("WIFI"))
        assertEquals(QrVerdict.Rejected("Este QR no es de pago"), frame(100, seen("WIFI")))
        repeat(10) { assertNull(frame(200L + it * 100, seen("WIFI"))) }
        assertIs<QrFlow.State.Rejected>(flow.state)
        // A payment code put in front replaces the rejected one.
        assertNull(frame(1_300, seen("PAY1")))
        assertEquals(QrVerdict.Accepted, frame(1_400, seen("PAY1")))
    }

    @Test
    fun rejectedCodeFadesAfterLeavingAndIsAnnouncedAgainWhenBack() {
        frame(0, seen("WIFI"))
        frame(100, seen("WIFI"))
        frame(800)
        assertIs<QrFlow.State.Rejected>(flow.state)
        frame(1_400)
        assertEquals(QrFlow.State.Idle, flow.state)
        frame(1_500, seen("WIFI"))
        assertIs<QrVerdict.Rejected>(frame(1_600, seen("WIFI")))
    }

    @Test
    fun deliveredCodeIsIgnoredUntilItLeavesTheView() {
        frame(0, seen("PAY1"))
        frame(100, seen("PAY1"))
        flow.delivered(600)
        assertEquals(QrFlow.State.Idle, flow.state)
        repeat(20) { assertNull(frame(700L + it * 100, seen("PAY1"))) }
        assertEquals(QrFlow.State.Idle, flow.state)
        frame(2_700)
        frame(3_800)
        assertNull(frame(3_900, seen("PAY1")))
        assertEquals(QrVerdict.Accepted, frame(4_000, seen("PAY1")))
    }

    @Test
    fun stillImagesNeedOneSightingAndPreferTheLargestCode() {
        assertEquals(QrVerdict.Accepted, frame(0, QrSighting("WIFI", quad(0f, 40f)), seen("PAY1", x = 200f), needed = 1))
        assertEquals("PAY1", (flow.state as QrFlow.State.Accepted).raw)
    }

    @Test
    fun numbersNeedRepeatedReadingsAndCardsNeedFewer() {
        val tracker = NumberTracker()
        val box = Box(0f, 0f, 200f, 30f)
        val card = NumberHit(NumberKind.CARD, "9204129951234568", box)
        val phone = NumberHit(NumberKind.PHONE, "51234567", box.copy(top = 100f, bottom = 130f))
        assertEquals(emptyList(), tracker.update(listOf(card, phone), 0))
        assertEquals(listOf("9204129951234568"), tracker.update(listOf(card, phone), 100).map { it.digits })
        assertEquals(listOf("9204129951234568", "51234567"), tracker.update(listOf(card, phone), 200).map { it.digits })
    }

    @Test
    fun competingReadingsOfOneSpotKeepTheMostRepeated() {
        val tracker = NumberTracker()
        val right = NumberHit(NumberKind.PHONE, "51234567", Box(0f, 0f, 100f, 20f))
        val wrong = NumberHit(NumberKind.PHONE, "51234561", Box(2f, 0f, 102f, 20f))
        listOf(right, wrong, right, right, wrong, right, wrong).forEachIndexed { i, hit -> tracker.update(listOf(hit), i * 100L) }
        val result = tracker.update(emptyList(), 700)
        assertEquals(listOf("51234567"), result.map { it.digits })
    }

    @Test
    fun numbersStayInTheTrayBrieflyAfterLeavingTheView() {
        val tracker = NumberTracker()
        val phone = NumberHit(NumberKind.PHONE, "51234567", Box(0f, 0f, 100f, 20f))
        repeat(3) { tracker.update(listOf(phone), it * 100L) }
        assertTrue(tracker.update(emptyList(), 300).single().visible)
        assertEquals(false, tracker.update(emptyList(), 1_000).single().visible)
        assertEquals(emptyList(), tracker.update(emptyList(), 3_000))
    }
}
