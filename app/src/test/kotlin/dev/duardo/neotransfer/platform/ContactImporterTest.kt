package dev.duardo.neotransfer.platform

import dev.duardo.neotransfer.data.ContactPhone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ContactImporterTest {
    @Test fun keepsAllDistinctCubanMobilesAndLabelsFromOneContact() {
        assertEquals(
            listOf(ContactPhone("51234567", "Casa"), ContactPhone("57654321", "Trabajo")),
            validCubanMobiles(listOf(
                ContactPhone("+53 5 123 4567", "Casa"),
                ContactPhone("5765-4321", "Trabajo"),
                ContactPhone("0053 51234567", "Duplicado"),
                ContactPhone("72123456", "Fijo"),
            )),
        )
    }

    @Test fun rejectsUnavailableOrMalformedNumbersInsteadOfSavingPartialData() {
        assertFailsWith<IllegalArgumentException> {
            validCubanMobiles(listOf(ContactPhone("72123456"), ContactPhone("+53 51234567 ext 2")))
        }
    }
}
