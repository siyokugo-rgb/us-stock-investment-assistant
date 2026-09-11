package issuer

/**
 * SEC CIK の正規形ヘルパー。
 *
 * 正規形: ちょうど 10 桁の数字（先頭ゼロ埋め）。
 * CIK は Issuer 側 external identifier であり、SecurityId ではない。
 * 本オブジェクトは SecurityId / IssuerId を生成しない。
 */
object Cik {
    private val CANONICAL = Regex("^\\d{10}$")

    fun isCanonical(value: String): Boolean = value.matches(CANONICAL)

    /**
     * 数字文字列を 10 桁ゼロ埋めで正規化する。
     * 空白のみ・非数字・11桁以上は Fail-Closed。
     */
    fun normalize(raw: String): String {
        val trimmed = raw.trim()
        require(trimmed.isNotBlank()) { "CIK must not be blank" }
        require(trimmed.all { it.isDigit() }) { "CIK must be numeric, got: '$raw'" }
        require(trimmed.length <= 10) { "CIK too long: '$raw'" }
        val normalized = trimmed.padStart(10, '0')
        require(isCanonical(normalized)) { "CIK must be exactly 10 digits, got: '$raw'" }
        return normalized
    }
}
