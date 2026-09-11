package fundamentals

import issuer.IssuerId
import java.math.BigDecimal
import java.time.LocalDate

/**
 * CompanyFacts 由来の raw financial fact（Issuer 側）。
 *
 * taxonomy / concept / unit / period / accession を失う
 * `Map<String, BigDecimal>` への変換は禁止。
 * SecurityId は持たない（Issuer ↔ Security は明示 relation で解決）。
 *
 * [filed] は filing 日付であり historical knownAt ではない。
 * 本型から knownAt を生成する API は提供しない。
 *
 * [accessionNumber] は canonical SEC 形式 `##########-##-######` 必須。
 */
data class RawFinancialFact(
    val issuerId: IssuerId,
    val taxonomy: String,
    val concept: String,
    val unit: String,
    val value: BigDecimal,
    val start: LocalDate?,
    val end: LocalDate,
    val fy: Int?,
    val fp: String?,
    val form: String,
    val filed: LocalDate,
    val frame: String?,
    val accessionNumber: String,
) {
    init {
        require(taxonomy.isNotBlank()) { "taxonomy must not be blank" }
        require(concept.isNotBlank()) { "concept must not be blank" }
        require(unit.isNotBlank()) { "unit must not be blank" }
        require(form.isNotBlank()) { "form must not be blank" }
        AccessionNumberFormat.requireCanonical(accessionNumber)
        if (start != null) {
            require(!start.isAfter(end)) { "start ($start) must not be after end ($end)" }
        }
    }

    /** instant / duration の区別。fy / fp / frame は period identity に使わない。 */
    fun period(): FactPeriod =
        if (start == null) {
            FactPeriod.InstantPoint(end = end)
        } else {
            FactPeriod.Duration(start = start, end = end)
        }
}
