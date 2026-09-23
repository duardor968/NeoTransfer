package dev.duardo.neotransfer

import android.content.Context
import dev.duardo.neotransfer.core.*
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

enum class ActionKind(val label: String) {
    TRANSFER("Transferencia"), QR("Pago QR"), RECHARGE("Recarga"), ELECTRICITY("Electricidad"), TELEPHONE("Teléfono")
}

class MoneyAction(
    val kind: ActionKind,
    val bank: Bank,
    val destination: String,
    val amount: Money,
    val source: String = "0000",
    val phone: String? = null,
    val qr: QrPayment? = null,
    val description: String = "",
) {
    fun transferRequest() = TransferRequest(bank, destination, amount, source, phone)
    override fun toString() = "MoneyAction($kind, $bank)"
}

class PendingRecord(val action: MoneyAction, val subscription: Int, val startedAt: Instant,
                    var refreshTaken: Boolean = false, val id: String = java.util.UUID.randomUUID().toString()) {
    val transfer = if (action.kind == ActionKind.TRANSFER) PendingTransfer(action.transferRequest(), startedAt, subscription) else null
}

data class Recipient(val name: String, val card: String, val phone: String?)
class UncertainTransfer(val id: String, val request: TransferRequest, val subscription: Int, val startedAt: Instant) {
    fun matches(message: BankMessage, time: Instant, sim: Int?): Boolean =
        PendingTransfer(request, startedAt, subscription).accept(message, time, sim)
}

/** Only non-secret metadata. The banking PIN lives exclusively in BankPinVault. */
class BankStore(context: Context) {
    private val prefs = context.getSharedPreferences("bank_state", Context.MODE_PRIVATE)
    var bank: Bank
        get() = Bank.valueOf(prefs.getString("bank", Bank.BANDEC.name)!!)
        set(value) { check(prefs.edit().putString("bank", value.name).commit()) }
    var subscription: Int
        get() = prefs.getInt("subscription", -1)
        set(value) { check(prefs.edit().putInt("subscription", value).commit()) }

    fun pending(): PendingRecord? = prefs.getString("pending", null)?.let {
        val json = JSONObject(it)
        PendingRecord(MoneyAction(ActionKind.valueOf(json.getString("kind")), Bank.valueOf(json.getString("bank")),
            json.getString("destination"), Money(json.getString("amount").toBigDecimal(), Currency.valueOf(json.getString("currency"))),
            json.getString("source"), json.optString("phone").takeIf(String::isNotEmpty)),
            json.getInt("subscription"), Instant.ofEpochMilli(json.getLong("started")), json.getBoolean("refresh"), json.getString("id"))
    }

    fun savePending(record: PendingRecord?) {
        val json = record?.let { r -> JSONObject().apply {
            put("id", r.id)
            put("kind", r.action.kind.name); put("bank", r.action.bank.name)
            put("destination", r.action.destination); put("amount", r.action.amount.amount.toPlainString())
            put("currency", r.action.amount.currency.name); put("source", r.action.source)
            put("phone", r.action.phone.orEmpty()); put("subscription", r.subscription)
            put("started", r.startedAt.toEpochMilli()); put("refresh", r.refreshTaken)
        }.toString() }
        // Commit before touching the modem. A failed write must prevent submission.
        val edit = prefs.edit().putString("pending", json)
        if (record != null && !record.refreshTaken) edit.putString("refresh", refreshJson(record.action.bank, record.subscription, record.startedAt.plusSeconds(10)))
        check(edit.commit()) { "No se pudo guardar la operación" }
    }

    fun usedReference(bank: Bank, reference: String): Boolean =
        prefs.getStringSet("receipts", emptySet())!!.contains("${bank.name}:$reference")

    fun confirm(bank: Bank, reference: String, subscription: Int, needsBalance: Boolean, refreshAt: Instant) {
        val used = prefs.getStringSet("receipts", emptySet())!!.toMutableSet().apply { add("${bank.name}:$reference") }
        val edit = prefs.edit().putStringSet("receipts", used).remove("pending")
        if (needsBalance) edit.putString("refresh", refreshJson(bank, subscription, refreshAt))
        else edit.remove("refresh")
        check(edit.commit())
    }

    private fun refreshJson(bank: Bank, subscription: Int, due: Instant) = JSONObject().apply {
        put("bank", bank.name); put("subscription", subscription); put("due", due.toEpochMilli())
    }.toString()

    fun refreshDue(bank: Bank, subscription: Int, now: Instant): Boolean = prefs.getString("refresh", null)?.let {
        val json = JSONObject(it)
        json.getString("bank") == bank.name && json.getInt("subscription") == subscription && json.getLong("due") <= now.toEpochMilli()
    } ?: false

    fun takeRefresh() { check(prefs.edit().remove("refresh").commit()) }

    fun balances(bank: Bank, subscription: Int): Pair<Instant?, List<AccountBalance>> {
        val raw = prefs.getString("balance_${bank.name}_$subscription", null) ?: return null to emptyList()
        val json = JSONObject(raw)
        val rows = json.getJSONArray("accounts")
        return Instant.ofEpochMilli(json.getLong("at")) to List(rows.length()) { index ->
            val row = rows.getJSONObject(index)
            val currency = Currency.valueOf(row.getString("currency"))
            AccountBalance(if (row.isNull("account")) null else row.getString("account"),
                if (row.isNull("ledger")) null else Money(row.getString("ledger").toBigDecimal(), currency),
                Money(row.getString("available").toBigDecimal(), currency),
                if (row.isNull("label")) null else row.getString("label"))
        }
    }

    fun saveBalances(bank: Bank, subscription: Int, at: Instant, accounts: List<AccountBalance>) {
        val json = JSONObject().apply {
            put("at", at.toEpochMilli()); put("accounts", JSONArray().apply { accounts.forEach { row ->
                put(JSONObject().apply {
                    put("account", row.account); put("currency", row.available.currency.name)
                    put("ledger", row.ledger?.amount?.toPlainString()); put("available", row.available.amount.toPlainString())
                    put("label", row.label)
                })
            } })
        }
        check(prefs.edit().putString("balance_${bank.name}_$subscription", json.toString()).commit())
    }

    fun closeUncertain(record: PendingRecord) {
        val list = JSONArray(prefs.getString("uncertain", "[]"))
        if (record.action.kind == ActionKind.TRANSFER) list.put(JSONObject().apply {
            put("id", record.id)
            put("bank", record.action.bank.name); put("subscription", record.subscription)
            put("destination", record.action.destination); put("amount", record.action.amount.amount.toPlainString())
            put("currency", record.action.amount.currency.name); put("started", record.startedAt.toEpochMilli())
        })
        check(prefs.edit().putString("uncertain", list.toString()).remove("pending").commit())
    }

    fun uncertain(): List<UncertainTransfer> {
        val list = JSONArray(prefs.getString("uncertain", "[]"))
        return (0 until list.length()).map { index ->
            val item = list.getJSONObject(index)
            UncertainTransfer(item.getString("id"), TransferRequest(Bank.valueOf(item.getString("bank")), item.getString("destination"),
                Money(item.getString("amount").toBigDecimal(), Currency.valueOf(item.getString("currency")))),
                item.getInt("subscription"), Instant.ofEpochMilli(item.getLong("started")))
        }
    }

    fun ambiguousReceipt(message: BankMessage.TransferSent, time: Instant, subscription: Int?): Boolean =
        uncertain().any { it.matches(message, time, subscription) }

    fun resolveUncertain(id: String, bank: Bank, reference: String) {
        val list = JSONArray(prefs.getString("uncertain", "[]"))
        val remaining = JSONArray().apply {
            (0 until list.length()).map(list::getJSONObject).filter { it.getString("id") != id }.forEach { put(it) }
        }
        val used = prefs.getStringSet("receipts", emptySet())!!.toMutableSet().apply { add("${bank.name}:$reference") }
        check(prefs.edit().putString("uncertain", remaining.toString()).putStringSet("receipts", used).commit())
    }

    fun recipients(): List<Recipient> {
        val array = JSONArray(prefs.getString("recipients", "[]"))
        return List(array.length()) { index -> array.getJSONObject(index).let {
            Recipient(it.getString("name"), it.getString("card"), it.optString("phone").takeIf(String::isNotEmpty))
        } }
    }

    fun saveRecipient(recipient: Recipient) {
        require(recipient.name.isNotBlank() && recipient.card.matches(Regex("[0-9]{16}")))
        val entries = recipients().filterNot { it.card == recipient.card } + recipient
        val array = JSONArray().apply { entries.forEach { put(JSONObject().apply {
            put("name", it.name); put("card", it.card); put("phone", it.phone.orEmpty())
        }) } }
        check(prefs.edit().putString("recipients", array.toString()).commit())
    }
}
