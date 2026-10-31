package dev.duardo.neotransfer.platform

import android.app.Application
import dev.duardo.neotransfer.core.ledger.ActivityRow
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

/** `/activity` rows go through a real cursor and come back as they were. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class PeerSyncTest {
    @Test fun `activity rows survive the cursor`() {
        val rows = listOf(
            ActivityRow("op-1", "CARD_TRANSFER", "CONFIRMED", "Ana Prueba", 1_792_000_000_123, "6000.00", "CUP", "OUT", "9204000000000001",
                "9204990000000015", "phone:0r1-2A"),
            ActivityRow("op-2", "ELECTRICITY", "NOT_DONE", "Casa", 1_791_999_000_000, "", "", "OUT", "", "", ""),
        )
        assertEquals(rows, activityCursor(rows).use(::activityRows))
    }
}
