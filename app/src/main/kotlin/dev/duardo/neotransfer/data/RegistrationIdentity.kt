package dev.duardo.neotransfer.data

import dev.duardo.neotransfer.core.ProfileId
import dev.duardo.neotransfer.core.ProviderId
import dev.duardo.neotransfer.core.ProviderIdentity

/** Linked instruments can have a different product profile from their authentication account. */
fun RegistrationRecord.productIdentity(instrumentProfile: String?): ProviderIdentity? = runCatching {
    val provider = ProviderId.valueOf(providerId)
    val registrationProfile = ProfileId.valueOf(profileId)
    val product = instrumentProfile?.let(ProfileId::valueOf) ?: registrationProfile
    require(product == registrationProfile || provider == ProviderId.MITRANSFER &&
        registrationProfile == ProfileId.PERSONAL && product in setOf(ProfileId.CLASSIC, ProfileId.CLASSIC_BUSINESS))
    ProviderIdentity(provider, product)
}.getOrNull()
