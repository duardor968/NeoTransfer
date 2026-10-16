package dev.duardo.neotransfer.engine

import dev.duardo.neotransfer.core.model.Contact
import java.text.Collator
import java.util.Locale

/**
 * The people the app knows: everyone in the phone's address book, with the cards the app keeps for them (stored
 * as `phone:<lookup key>`), plus the contacts kept only in the app. An address-book person's name and phones always
 * come from the address book; the stored copy is what a backup carries.
 */
internal object Directory {
    private const val PREFIX = "phone:"

    /** Spanish order, ignoring accents and case: Ángel next to Ana, Ñ after N. */
    private val byName: Collator = Collator.getInstance(Locale.forLanguageTag("es")).apply { strength = Collator.PRIMARY }

    fun id(person: PhoneContact) = PREFIX + person.key

    /** The address-book key behind a contact id, if it has one. */
    fun key(contactId: String): String? = contactId.takeIf { it.startsWith(PREFIX) }?.removePrefix(PREFIX)

    /** Everyone, by name; [agenda] null when the address book cannot be read. */
    fun merge(stored: List<Contact>, agenda: List<PhoneContact>?): List<Contact> {
        val byId = stored.associateBy { it.id }
        val people = agenda.orEmpty().map { person ->
            val own = byId[id(person)]
            Contact(id(person), person.name, person.phones, own?.cards.orEmpty(), own?.labels.orEmpty())
        }
        val ids = people.mapTo(hashSetOf()) { it.id }
        return (people + stored.filter { it.id !in ids }).sortedWith(compareBy(byName) { it.name })
    }

    /**
     * Stored records to rewrite so each address-book person carries the cards saved for them: a contact kept only in
     * the app, or one whose address-book entry changed key, joins the one person it shares a phone with. A contact
     * that shares phones with several people stays as it is. Returns what to save and the ids to delete.
     */
    fun link(stored: List<Contact>, agenda: List<PhoneContact>): Pair<List<Contact>, List<String>> {
        val ids = agenda.mapTo(hashSetOf(), ::id)
        val records = stored.filter { it.id in ids }.associateByTo(linkedMapOf()) { it.id }
        val changed = linkedSetOf<String>()
        val deleted = mutableListOf<String>()
        for (contact in stored.filter { it.id !in ids }) {
            val person = agenda.filter { person -> person.phones.any { it in contact.phones } }.singleOrNull() ?: continue
            val target = id(person)
            val current = records[target]
            records[target] = Contact(target, person.name, person.phones, (current?.cards.orEmpty() + contact.cards).distinct(),
                contact.labels + current?.labels.orEmpty())
            changed += target
            deleted += contact.id
        }
        return changed.map(records::getValue) to deleted
    }
}
