package sec.poc

/**
 * EDGAR accession number（accepted submission の一意識別子 / submission version id）。
 *
 * 公式: 先頭 10 桁は **submitting (login) CIK** であり、
 * Issuer CIK / SecurityId と同一とは限らない（third-party filing agent があり得る）。
 * accession prefix から Issuer / Security を推定してはならない。
 * SecurityId / IssuerId の代わりに使ってはならない。
 *
 * 注: SEC filing 上の Filer（registrant）と、accession prefix の login/submitting CIK は別概念。
 */
@JvmInline
value class SecAccessionNumber private constructor(val value: String) {
    init {
        require(value.matches(PATTERN)) {
            "Accession must match NNNNNNNNNN-YY-NNNNNN, got: '$value'"
        }
    }

    /** 先頭 10 桁 = submitting (login) CIK（Issuer CIK / registrant と同一とは限らない）。 */
    val submittingEntityCik: SecCik get() = SecCik.parse(value.substring(0, 10))

    val compactNoDashes: String get() = value.replace("-", "")

    companion object {
        private val PATTERN = Regex("^\\d{10}-\\d{2}-\\d{6}$")

        fun parse(raw: String): SecAccessionNumber {
            val trimmed = raw.trim()
            if (trimmed.isEmpty()) {
                throw SecEdgarPocException("Accession number must not be blank")
            }
            if (!trimmed.matches(PATTERN)) {
                throw SecEdgarPocException("Invalid accession number: '$raw'")
            }
            return SecAccessionNumber(trimmed)
        }
    }
}

/** Archives パス用: 先頭ゼロを除いた CIK（公式例に合わせる）。 */
fun SecCik.forArchivesPath(): String = value.trimStart('0').ifEmpty { "0" }
