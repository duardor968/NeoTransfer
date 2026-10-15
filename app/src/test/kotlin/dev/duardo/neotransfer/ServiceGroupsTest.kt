package dev.duardo.neotransfer

import dev.duardo.neotransfer.core.*
import dev.duardo.neotransfer.enzona.EnzonaInvoiceService
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ServiceGroupsTest {
    private val bpa = ProviderIdentity.forBank(Bank.BPA)
    private val bandec = ProviderIdentity.forBank(Bank.BANDEC)
    private fun spec(id: String) = requireNotNull(OperationCatalog.find(id))

    @Test fun nautaHomeKeepsBankDebtVisibleWhenTopUpUsesEnzona() {
        val group = serviceGroups(OperationCatalog.all).single { it.utility == ServiceUtility.NAUTA_HOME }
        assertEquals(setOf("Recargar", "Pagar deuda"), group.actions.map { it.title }.toSet())
        assertNull(enzonaServiceDestination(group.utility)) // Enter the group instead of hiding its bank action.
        val recharge = group.actions.single { it.title == "Recargar" }
        assertEquals(EnzonaInvoiceService.NAUTA_HOME, enzonaServiceDestination(group.utility, recharge))
        val debt = group.actions.single { it.title == "Pagar deuda" }
        assertNull(enzonaServiceDestination(group.utility, debt))
        assertSame(spec("service.nauta.debt"), debt.resolve(bpa)?.spec)
        assertSame(spec("service.nauta.debt"), debt.resolve(bandec)?.spec)
        assertEquals(EnzonaInvoiceService.NAUTA, enzonaServiceDestination(ServiceUtility.NAUTA))
    }

    @Test fun customerFindsTheSixUtilitiesWithoutTechnicalCategories() {
        val groups = serviceGroups(OperationCatalog.all)
        assertEquals(listOf("Electricidad", "Agua", "Teléfono fijo", "Nauta Hogar", "Nauta", "Cubacel"),
            groups.map { it.utility.title })
        assertTrue(groups.all { it.actions.isNotEmpty() })
        assertTrue(groups.flatMap { it.actions }.flatMap { it.variants }.all { it.spec in OperationCatalog.all })
    }

    @Test fun payingTheSameTelephoneServiceWithTwoBanksPreservesBothContracts() {
        val bpaPayment = spec("service.telephone.bpa")
        val bandecPayment = spec("service.telephone")
        val group = serviceGroups(listOf(bpaPayment, bandecPayment)).single()
        assertEquals(ServiceUtility.TELEPHONE, group.utility)
        val action = group.actions.single()
        assertSame(bpaPayment, action.resolve(bpa)?.spec)
        assertSame(bandecPayment, action.resolve(bandec)?.spec)
        assertEquals(setOf(bpaPayment, bandecPayment), action.variants.map { it.spec }.toSet())
        assertTrue(bpaPayment.fields.single { it.key == "amount" }.required)
        assertFalse(bandecPayment.fields.single { it.key == "amount" }.required)
        assertTrue(bpaPayment.validate(ServiceRequest(bpaPayment.id, bandec)).any { it.fieldKey == "operation" })
        assertTrue(bandecPayment.validate(ServiceRequest(bandecPayment.id, bpa)).any { it.fieldKey == "operation" })
        assertNull(action.resolve(null))
        assertNull(action.resolve(ProviderIdentity(ProviderId.CUBACEL)))
    }

    @Test fun aSharedPaymentStillOffersAllCompatibleOriginsInTheForm() {
        val payment = spec("service.electricity")
        val action = serviceGroups(listOf(payment)).single().actions.single()
        for (identity in listOf(bpa, bandec)) {
            val variant = requireNotNull(action.resolve(identity))
            assertSame(payment, variant.spec)
            val request = ServiceRequest(variant.spec.id, identity, SourceSelector.Explicit("1234567890123456"),
                values = mapOf("invoice" to "1234567890123"))
            assertTrue(variant.spec.validate(request).isEmpty())
        }
    }

    @Test fun billInquiryStartsWithTheUtilityChosenAndKeepsEachBankVariant() {
        val bpaQuery = spec("service.query.bpa")
        val bandecQuery = spec("service.query.bandec")
        val groups = serviceGroups(listOf(bpaQuery, bandecQuery))
        val electricity = groups.single { it.utility == ServiceUtility.ELECTRICITY }
        for (identity in listOf(bpa, bandec)) {
            val action = requireNotNull(electricity.actions.single().resolve(identity))
            assertEquals(mapOf("serviceType" to "electricity"), action.initialValues)
            assertEquals(setOf(identity), action.spec.identities)
            val request = ServiceRequest(action.spec.id, identity,
                values = action.initialValues + ("invoice" to "1234567890123"))
            assertTrue(action.spec.validate(request).isEmpty())
            assertEquals(SourcePolicy.NONE, action.spec.sourcePolicy)
            assertTrue(action.spec.validate(ServiceRequest(action.spec.id, identity,
                SourceSelector.Explicit("1234567890123456"), values = request.values)).any { it.fieldKey == "source" })
        }
        assertEquals(1, electricity.actions.size)
        assertEquals(2, electricity.actions.single().variants.size)
        assertTrue(groups.single { it.utility == ServiceUtility.TELEPHONE }.actions.single().variants.all {
            it.initialValues["serviceType"] == "telephone"
        })
        val water = groups.single { it.utility == ServiceUtility.WATER }
        assertEquals(3, water.actions.size)
        assertEquals(setOf("water.havana", "water.other", "water.varadero"),
            water.actions.flatMap { it.variants }.map { it.initialValues.getValue("serviceType") }.toSet())
        assertTrue(water.actions.flatMap { it.variants }.none { it.initialValues["serviceType"] == "gas" })
    }

    @Test fun missingOrIncompatibleBankRequiresAnExplicitChoiceEvenForOneRemainingContract() {
        val query = spec("service.query.bpa")
        val action = serviceGroups(listOf(query)).single { it.utility == ServiceUtility.ELECTRICITY }.actions.single()
        assertNull(action.resolve(bandec))
        assertNull(action.resolve(null))
        val explicitChoice = action.variants.single()
        assertSame(query, explicitChoice.spec)
        assertEquals(mapOf("serviceType" to "electricity"), explicitChoice.initialValues)
        assertSame(explicitChoice, action.resolve(bpa))
    }

    @Test fun anUnavailableInquiryOptionNeverBecomesAServiceOrEmptyGroup() {
        val query = spec("service.query.bpa")
        val limited = query.copy(fields = query.fields.map { field ->
            if (field.key == "serviceType") field.copy(options = field.options.filter { it.value == "electricity" }) else field
        })
        val groups = serviceGroups(listOf(limited))
        assertEquals(listOf(ServiceUtility.ELECTRICITY), groups.map { it.utility })
        assertEquals(mapOf("serviceType" to "electricity"), groups.single().actions.single().variants.single().initialValues)
        assertTrue(serviceGroups(listOf(limited.copy(implementationStatus = ImplementationStatus.CONTRACT_PENDING))).isEmpty())
    }

    @Test fun scopedOutOperationsCannotCreateAnEntryEvenInsideAnApprovedUtility() {
        val excluded = (BankingOperations.all + ServiceOperations.all + TelecomOperations.all).filter {
            it.id in setOf("bpa.balance", "service.gas", "service.fuel", "service.miturno.reserve", "service.stamp",
                "service.plans", "service.contracted.add", "service.contracted.query", "service.contracted.remove",
                "service.contracted.pay", "cubacel.voucher", "cubacel.advance", "cubacel.call")
        }
        assertTrue(excluded.isNotEmpty())
        assertTrue(serviceGroups(excluded).isEmpty())
        val payment = spec("service.nauta")
        val groups = serviceGroups(excluded + payment)
        assertEquals(listOf(ServiceUtility.NAUTA), groups.map { it.utility })
        assertEquals(listOf(payment), groups.single().actions.flatMap { it.variants }.map { it.spec })
    }

    @Test fun nautaAndNautaHomeDoNotPromiseAnInquiryAndCubacelHasItsRequestedActions() {
        val groups = serviceGroups(OperationCatalog.all)
        for (utility in listOf(ServiceUtility.NAUTA, ServiceUtility.NAUTA_HOME)) {
            assertTrue(groups.single { it.utility == utility }.actions.flatMap { it.variants }.none { it.spec.effect == OperationEffect.QUERY })
        }
        val cubacel = groups.single { it.utility == ServiceUtility.CUBACEL }
        val variants = cubacel.actions.flatMap { it.variants }
        assertTrue(variants.map { it.spec.id }.containsAll(listOf("service.mobile", "cubacel.plans", "cubacel.transfer", "cubacel.pin")))
        assertTrue(variants.any { it.spec.effect == OperationEffect.QUERY })
        val pin = cubacel.actions.single { it.variants.any { variant -> variant.spec.id == "cubacel.pin" } }
        assertSame(spec("cubacel.pin"), pin.resolve(bandec)?.spec)
        assertSame(spec("cubacel.pin"), pin.resolve(null)?.spec)
    }

}
