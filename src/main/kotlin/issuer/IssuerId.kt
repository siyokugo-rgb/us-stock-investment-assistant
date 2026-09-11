package issuer

/**
 * Ticker / CIK とは独立した内部 Issuer identity。
 * Ticker 文字列・CIK 文字列からこの値を導出してはならない。
 * 外部 identifier そのものを内部 ID にしてはならない。
 */
@JvmInline
value class IssuerId(val value: String) {
    init {
        require(value.isNotBlank()) { "issuerId must not be blank" }
    }
}
