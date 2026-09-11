package sec.poc

/**
 * SEC CIK。submissions API では 10 桁ゼロ埋めが必須。
 * Ticker から推測して生成してはならない。
 */
@JvmInline
value class SecCik private constructor(val value: String) {
    init {
        require(value.matches(CIK_PATTERN)) {
            "CIK must be exactly 10 digits with leading zeros, got: '$value'"
        }
    }

    companion object {
        private val CIK_PATTERN = Regex("^\\d{10}$")

        fun parse(raw: String): SecCik {
            val trimmed = raw.trim()
            if (trimmed.isEmpty()) {
                throw SecEdgarPocException("CIK must not be blank")
            }
            if (!trimmed.all { it.isDigit() }) {
                throw SecEdgarPocException("CIK must be numeric, got: '$raw'")
            }
            if (trimmed.length > 10) {
                throw SecEdgarPocException("CIK too long: '$raw'")
            }
            return SecCik(trimmed.padStart(10, '0'))
        }

        fun fromInt(cik: Int): SecCik {
            require(cik >= 0) { "CIK must not be negative: $cik" }
            return parse(cik.toString())
        }
    }
}
