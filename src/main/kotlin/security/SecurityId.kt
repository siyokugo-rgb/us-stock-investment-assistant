package security

/**
 * Ticker とは独立した内部識別子。
 * Ticker 文字列からこの値を導出してはならない。
 */
@JvmInline
value class SecurityId(val value: String) {
    init {
        require(value.isNotBlank()) { "securityId must not be blank" }
    }
}
