package fundamentals

/**
 * SEC accession の canonical 形式検証（domain 側の最小 validation）。
 *
 * 形式: `##########-##-######`（例: `0000320193-25-000079`）
 *
 * - `sec.poc.SecAccessionNumber` は PoC 層専用のため、本 domain からは依存しない。
 * - accession prefix から IssuerId / SecurityId を推測しない。
 * - prefix CIK を issuer CIK とみなさない。
 * - malformed accession を version 候補として保持しない（Fail-Closed）。
 */
object AccessionNumberFormat {
    private val CANONICAL = Regex("^\\d{10}-\\d{2}-\\d{6}$")

    fun isCanonical(value: String): Boolean = value.matches(CANONICAL)

    /**
     * @return canonical accession 文字列
     * @throws IllegalArgumentException blank / 形式不正の場合
     */
    fun requireCanonical(raw: String): String {
        val trimmed = raw.trim()
        require(trimmed.isNotBlank()) { "accessionNumber must not be blank" }
        require(isCanonical(trimmed)) {
            "accessionNumber must match ##########-##-######, got: '$raw'"
        }
        return trimmed
    }
}
