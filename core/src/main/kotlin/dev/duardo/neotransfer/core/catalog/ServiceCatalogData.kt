package dev.duardo.neotransfer.core


data class BankBranch(
    val bank: Bank,
    val provinceCode: String,
    val municipalityDpa: String,
    val code: String,
    val name: String,
)

enum class MiTurnoType { CADECA, GAS, BALITA, BANK }

data class MiTurnoMunicipality(val code: String, val name: String, val provinceCode: String) {
    /** Some provider areas (e.g. Santiago's casas comerciales 10–12) have no national DPA code. */
    val dpaCode: String? get() = TerritoryCatalog.normalizeMunicipality(provinceCode, code.padStart(2, '0'))
}

data class MiTurnoBranch(
    val type: MiTurnoType,
    val code: String,
    val name: String,
    val provinceCode: String,
    /** Original municipal component, present only in the dedicated balita branch asset. */
    val municipalityCode: String?,
)

data class MiTurnoService(val branchCode: String, val code: String, val name: String)
data class StampEntity(val code: String, val name: String, val ministry: String)
data class StampDenomination(val amount: String, val name: String, val currency: String)
data class WithdrawalEntity(val code: String, val name: String, val currency: String)
data class TrafficArticle(val code: String, val description: String)
data class TrafficSection(val articleCode: String, val code: String, val description: String, val dangerLevel: String)

/**
 * Factual selector data from Transfermóvil APK 1.260416 assets. The TSV resources preserve
 * original identifiers and labels; they do not establish current availability or network acceptance.
 */
object ServiceCatalogData {
    private fun rows(file: String, columns: Int): List<List<String>> {
        val stream = checkNotNull(javaClass.getResourceAsStream("/service-catalog/$file")) { "Missing catalog $file" }
        return stream.bufferedReader(Charsets.UTF_8).useLines { lines ->
            lines.filter { it.isNotEmpty() }.map { line ->
                line.split('\t', limit = columns).also { require(it.size == columns) { "Invalid catalog row in $file" } }
            }.toList()
        }
    }

    private val allBankBranches: List<BankBranch> by lazy {
        rows("bank-branches.tsv", 5).map { (bank, province, municipality, code, name) ->
            BankBranch(Bank.valueOf(bank), province, municipality, code, name)
        }
    }

    val dpaProvinces: List<TerritoryProvince> get() = TerritoryCatalog.provinces
    fun dpaMunicipalities(provinceCode: String): List<TerritoryMunicipality> = TerritoryCatalog.municipalities(provinceCode)
    fun municipalityDpa(provinceCode: String, municipalityCode: String): String? =
        TerritoryCatalog.normalizeMunicipality(provinceCode, municipalityCode)

    fun bankProvinces(bank: Bank): List<TerritoryProvince> {
        val codes = allBankBranches.filter { it.bank == bank }.mapTo(mutableSetOf()) { it.provinceCode }
        return dpaProvinces.filter { it.code in codes }
    }

    fun bankMunicipalities(bank: Bank, provinceCode: String): List<TerritoryMunicipality> {
        val codes = allBankBranches.filter { it.bank == bank && it.provinceCode == provinceCode }
            .mapTo(mutableSetOf()) { it.municipalityDpa }
        return dpaMunicipalities(provinceCode).filter { it.code in codes }
    }

    fun bankBranches(bank: Bank, provinceCode: String, municipalityCode: String): List<BankBranch> {
        val dpa = municipalityDpa(provinceCode, municipalityCode) ?: return emptyList()
        return allBankBranches.filter { it.bank == bank && it.provinceCode == provinceCode && it.municipalityDpa == dpa }
    }

    fun bankBranch(bank: Bank, provinceCode: String, municipalityCode: String, code: String): BankBranch? =
        bankBranches(bank, provinceCode, municipalityCode).firstOrNull { it.code == code }

    private val allMiTurnoBranches: List<MiTurnoBranch> by lazy {
        rows("miturno-branches.tsv", 5).map { (type, code, name, province, municipality) ->
            MiTurnoBranch(MiTurnoType.valueOf(type), code, name, province, municipality.ifEmpty { null })
        }
    }
    private val allMiTurnoMunicipalities: List<MiTurnoMunicipality> by lazy {
        rows("miturno-municipalities.tsv", 3).map { (code, name, province) -> MiTurnoMunicipality(code, name, province) }
    }
    private val allMiTurnoServices: List<MiTurnoService> by lazy {
        rows("miturno-services.tsv", 3).map { (branch, code, name) -> MiTurnoService(branch, code, name) }
    }

    fun miturnoProvinces(type: MiTurnoType): List<TerritoryProvince> {
        val codes = allMiTurnoBranches.filter { it.type == type }.mapTo(mutableSetOf()) { it.provinceCode }
        return dpaProvinces.filter { it.code in codes }
    }

    /** Only the balita branch asset with municipal codes has a matching municipality catalog. */
    fun miturnoMunicipalities(type: MiTurnoType, provinceCode: String): List<MiTurnoMunicipality> {
        if (type != MiTurnoType.BALITA) return emptyList()
        val codes = allMiTurnoBranches.filter { it.type == type && it.provinceCode == provinceCode }
            .mapNotNullTo(mutableSetOf()) { it.municipalityCode?.padStart(2, '0') }
        return allMiTurnoMunicipalities.filter { it.provinceCode == provinceCode && it.code.padStart(2, '0') in codes }
    }

    fun miturnoBranches(type: MiTurnoType, provinceCode: String, municipalityCode: String? = null): List<MiTurnoBranch> {
        val municipality = municipalityCode?.let {
            val selected = miturnoMunicipalities(type, provinceCode).firstOrNull { option ->
                it == option.code || it == option.code.padStart(2, '0') || it == option.dpaCode
            } ?: return emptyList()
            selected.code.padStart(2, '0')
        }
        return allMiTurnoBranches.filter { branch ->
            branch.type == type && branch.provinceCode == provinceCode &&
                (if (municipality == null) branch.municipalityCode == null
                 else branch.municipalityCode?.padStart(2, '0') == municipality)
        }
    }

    fun miturnoBranch(type: MiTurnoType, provinceCode: String, municipalityCode: String?, code: String): MiTurnoBranch? =
        miturnoBranches(type, provinceCode, municipalityCode).firstOrNull { it.code == code }

    /** The service mapping is supplied only for MiTurno's banking branches. */
    fun miturnoServices(branchCode: String): List<MiTurnoService> =
        if (allMiTurnoBranches.none { it.type == MiTurnoType.BANK && it.code == branchCode }) emptyList()
        else allMiTurnoServices.filter { it.branchCode == branchCode }

    val stampEntities: List<StampEntity> by lazy {
        rows("stamp-entities.tsv", 3).map { (code, name, ministry) -> StampEntity(code, name, ministry) }
    }
    val stampDenominations: List<StampDenomination> by lazy {
        rows("stamp-denominations.tsv", 3).map { (amount, name, currency) -> StampDenomination(amount, name, currency) }
    }
    fun stampEntity(code: String): StampEntity? = stampEntities.firstOrNull { it.code == code }
    fun stampDenomination(amount: String, currency: String): StampDenomination? =
        stampDenominations.firstOrNull { it.amount == amount && it.currency == currency }

    val withdrawalEntities: List<WithdrawalEntity> by lazy {
        rows("withdrawal-entities.tsv", 3).map { (code, name, currency) -> WithdrawalEntity(code, name, currency) }
    }
    fun withdrawalEntity(code: String): WithdrawalEntity? = withdrawalEntities.firstOrNull { it.code == code }

    val trafficArticles: List<TrafficArticle> by lazy {
        rows("traffic-articles.tsv", 2).map { (code, description) -> TrafficArticle(code, description) }
    }
    private val allTrafficSections: List<TrafficSection> by lazy {
        rows("traffic-sections.tsv", 4).map { (article, section, description, danger) ->
            TrafficSection(article, section, description, danger)
        }
    }
    fun trafficSections(articleCode: String): List<TrafficSection> =
        if (trafficArticles.none { it.code == articleCode }) emptyList()
        else allTrafficSections.filter { it.articleCode == articleCode }
    fun trafficSection(articleCode: String, sectionCode: String): TrafficSection? =
        trafficSections(articleCode).firstOrNull { it.code == sectionCode }

}
