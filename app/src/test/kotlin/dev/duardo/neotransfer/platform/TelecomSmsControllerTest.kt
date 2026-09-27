package dev.duardo.neotransfer.platform

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class TelecomSmsControllerTest {
    private class MemoryStore : TelecomSmsStore {
        val saved = mutableMapOf<String, TelecomSmsRecord>()
        var failNextPut = false
        override fun get(id: String) = saved[id]
        override fun put(record: TelecomSmsRecord): Boolean {
            if (failNextPut) { failNextPut = false; return false }
            saved[record.draft.id] = record
            return true
        }
        override fun records() = saved.values.toList()
    }

    private class Runtime : TelecomSmsRuntime {
        var permission = true
        var active = setOf(7)
        override fun isActiveSubscription(subscriptionId: Int) = subscriptionId in active
        override fun hasSendPermission() = permission
    }

    private class Transport : TelecomSmsTransport {
        val sent = mutableListOf<TelecomSmsDraft>()
        var throwAfterCapture = false
        var onSend: ((TelecomSmsDraft) -> Unit)? = null
        override fun send(draft: TelecomSmsDraft) {
            onSend?.invoke(draft)
            sent += draft
            if (throwAfterCapture) error("unknown after dispatch")
        }
    }

    private class Harness(val store: MemoryStore = MemoryStore(), val runtime: Runtime = Runtime(), val transport: Transport = Transport()) {
        private var sequence = 0
        fun controller() = TelecomSmsController(store, runtime, transport, { "token-${++sequence}" }, { 123L })
    }

    @Test fun `only four factual SMS actions can prepare and review freezes SIM destination and body`() {
        val h = Harness()
        val controller = h.controller()
        assertIs<TelecomSmsPrepare.InvalidAction>(controller.prepare("cubacel.emergency.ambulance", 7))
        assertIs<TelecomSmsPrepare.InvalidAction>(controller.prepare("cubacel.info.other", 7))
        assertIs<TelecomSmsPrepare.InactiveSim>(controller.prepare("cubacel.info.offers", 8))
        h.store.failNextPut = true
        assertIs<TelecomSmsPrepare.StorageFailed>(controller.prepare("cubacel.info.offers", 7))
        val draft = assertIs<TelecomSmsPrepare.Ready>(controller.prepare("cubacel.info.offers", 7)).draft
        assertEquals("2266", draft.destination)
        assertEquals("OFERTA", draft.body)
        assertEquals(7, draft.subscriptionId)
        assertEquals(TelecomSmsStatus.DRAFT, controller.record(draft.id)?.status)
        assertEquals(TelecomSmsSubmit.STALE_REVIEW, controller.sendAfterReview(draft.copy(body = "ALTERADO")))
        assertTrue(h.transport.sent.isEmpty())
    }

    @Test fun `permission and active SIM are checked again before the durable transition`() {
        val h = Harness()
        val controller = h.controller()
        val draft = assertIs<TelecomSmsPrepare.Ready>(controller.prepare("cubacel.info.tariffs", 7)).draft
        h.runtime.permission = false
        assertEquals(TelecomSmsSubmit.PERMISSION_REQUIRED, controller.sendAfterReview(draft))
        assertEquals(TelecomSmsStatus.DRAFT, controller.record(draft.id)?.status)
        h.runtime.permission = true
        h.runtime.active = emptySet()
        assertEquals(TelecomSmsSubmit.INACTIVE_SIM, controller.sendAfterReview(draft))
        assertTrue(h.transport.sent.isEmpty())
        h.runtime.active = setOf(7)
        h.store.failNextPut = true
        assertEquals(TelecomSmsSubmit.STORAGE_FAILED, controller.sendAfterReview(draft))
        assertTrue(h.transport.sent.isEmpty())
        assertEquals(TelecomSmsStatus.DRAFT, controller.record(draft.id)?.status)
    }

    @Test fun `submitted request stays unknown until one matching sent callback`() {
        val h = Harness()
        val controller = h.controller()
        val first = assertIs<TelecomSmsPrepare.Ready>(controller.prepare("cubacel.info.plans", 7)).draft
        val second = assertIs<TelecomSmsPrepare.Ready>(controller.prepare("cubacel.info.friends", 7)).draft
        var statusAtSend: TelecomSmsStatus? = null
        h.transport.onSend = { statusAtSend = h.store.get(it.id)?.status }
        assertEquals(TelecomSmsSubmit.UNKNOWN, controller.sendAfterReview(first))
        assertEquals(TelecomSmsStatus.UNKNOWN, statusAtSend)
        assertEquals(TelecomSmsStatus.UNKNOWN, controller.record(first.id)?.status)
        assertEquals(listOf(first), h.transport.sent)
        assertEquals(TelecomSmsSubmit.STALE_REVIEW, controller.sendAfterReview(first))
        assertFalse(controller.onSentCallback(first.id, second.callbackToken, true, -1))
        assertFalse(controller.onSentCallback(second.id, second.callbackToken, true, -1))
        assertEquals(TelecomSmsStatus.DRAFT, controller.record(second.id)?.status)
        assertTrue(controller.onSentCallback(first.id, first.callbackToken, true, -1))
        assertEquals(TelecomSmsStatus.SENT, controller.record(first.id)?.status)
        assertEquals(TelecomSmsSubmit.UNKNOWN, controller.sendAfterReview(second))
        assertFalse(controller.onSentCallback(second.id, first.callbackToken, true, -1))
        assertEquals(TelecomSmsStatus.UNKNOWN, controller.record(second.id)?.status)
        assertFalse(controller.onSentCallback(first.id, first.callbackToken, false, 1))
        assertEquals(TelecomSmsStatus.SENT, controller.record(first.id)?.status)
        assertEquals(2, h.transport.sent.size)
    }

    @Test fun `restart and transport exception never cause automatic resend or cross attribution`() {
        val h = Harness()
        val firstController = h.controller()
        val first = assertIs<TelecomSmsPrepare.Ready>(firstController.prepare("cubacel.info.offers", 7)).draft
        h.transport.throwAfterCapture = true
        assertEquals(TelecomSmsSubmit.UNKNOWN, firstController.sendAfterReview(first))
        val afterRestart = h.controller()
        assertEquals(TelecomSmsStatus.UNKNOWN, afterRestart.record(first.id)?.status)
        assertEquals(1, h.transport.sent.size)
        assertEquals(TelecomSmsSubmit.STALE_REVIEW, afterRestart.sendAfterReview(first))
        val second = assertIs<TelecomSmsPrepare.Ready>(afterRestart.prepare("cubacel.info.tariffs", 7)).draft
        assertTrue(afterRestart.onSentCallback(first.id, first.callbackToken, false, 42))
        assertEquals(TelecomSmsStatus.FAILED, afterRestart.record(first.id)?.status)
        assertEquals(TelecomSmsStatus.DRAFT, afterRestart.record(second.id)?.status)
        assertFalse(afterRestart.onSentCallback(second.id, first.callbackToken, true, -1))
        assertEquals(1, h.transport.sent.size)
    }
}
