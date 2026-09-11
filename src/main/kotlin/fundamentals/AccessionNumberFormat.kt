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
 * - silent trim / 正規化による受理は禁止。最初から canonical な入力のみ受理する。
 */
object AccessionNumberFormat {
    private val CANONICAL = Regex("^\\d{10}-\\d{2}-\\d{6}$")

    fun isCanonical(value: String): Boolean = value.matches(CANONICAL)

    /**
     * @return [raw] そのもの（canonical であることが保証される）
     * @throws IllegalArgumentException blank / 前後空白 / 形式不正の場合
     */
    fun requireCanonical(raw: String): String {
        require(raw.isNotEmpty()) { "accessionNumber must not be blank" }
        require(raw == raw.trim()) {
            "accessionNumber must already be canonical without leading/trailing whitespace, got: '$raw'"
        }
        require(isCanonical(raw)) {
            "accessionNumber must match ##########-##-######, got: '$raw'"
        }
        return raw
    }
}
