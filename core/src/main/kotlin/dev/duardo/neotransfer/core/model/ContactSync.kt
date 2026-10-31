package dev.duardo.neotransfer.core.model

/**
 * A stored contact as NeoTransfer and NeoTransfer+ keep and exchange it. [updated] is the epoch millis of the last change
 * made in this app, or taken with it from the other one (0 when it has not changed since the two apps sync). A [deleted]
 * record is a tombstone, its phones, cards and labels cleared: no screen shows it, and it keeps the deletion from coming
 * back from the other app.
 */
data class ContactRecord(val contact: Contact, val updated: Long, val deleted: Boolean = false)

/**
 * One row of the read-only provider each app exports for the other (`content://<applicationId>.sync/contacts`):
 * [phones] are 8-digit numbers one per line, [cards] one `<16 digits>\t<label or empty>` per line.
 */
data class ContactRow(val id: String, val name: String, val phones: String, val cards: String, val updated: Long, val deleted: Boolean)

/** The two-way sync of the stored contacts with the other app: each one reads the other's rows and [merge]s them in. */
object ContactSync {
    fun row(record: ContactRecord): ContactRow {
        val contact = record.contact
        return ContactRow(contact.id, contact.name, contact.phones.joinToString("\n"),
            contact.cards.joinToString("\n") { "$it\t${contact.labels[it].orEmpty()}" }, record.updated, record.deleted)
    }

    fun record(row: ContactRow): ContactRecord {
        val cards = lines(row.cards).map { it.substringBefore('\t') to it.substringAfter('\t', "") }
        val labels = cards.filter { it.second.isNotEmpty() }.toMap()
        return ContactRecord(Contact(row.id, row.name, lines(row.phones), cards.map { it.first }, labels), row.updated, row.deleted)
    }

    /** What deleting [contact] keeps at [at]: its id and name, nothing else. */
    fun tombstone(contact: Contact, at: Long) = ContactRecord(Contact(contact.id, contact.name), at, deleted = true)

    /**
     * The records to write locally after reading the other app's [remote] ones, by id: an unknown one is taken unless it
     * is a tombstone; a newer one is taken as it is, its [ContactRecord.updated] untouched so the other app does not take
     * it back as new; an older one loses. On a tie (both 0 on the first sync) the two are joined: phones and cards of both,
     * local ones first, local labels winning, deleted only if both are; written only when that changes the local one.
     */
    fun merge(local: Collection<ContactRecord>, remote: List<ContactRecord>): List<ContactRecord> {
        val ours = local.associateBy { it.contact.id }
        return remote.mapNotNull { theirs ->
            val mine = ours[theirs.contact.id]
            when {
                mine == null -> theirs.takeUnless { it.deleted }
                theirs.updated > mine.updated -> theirs
                theirs.updated < mine.updated -> null
                else -> join(mine, theirs).takeIf { it != mine }
            }
        }
    }

    private fun join(mine: ContactRecord, theirs: ContactRecord): ContactRecord {
        val a = mine.contact
        val b = theirs.contact
        val contact = a.copy(phones = (a.phones + b.phones).distinct(), cards = (a.cards + b.cards).distinct(), labels = b.labels + a.labels)
        return ContactRecord(contact, mine.updated, mine.deleted && theirs.deleted)
    }

    private fun lines(value: String) = value.split('\n').filter(String::isNotEmpty)
}
