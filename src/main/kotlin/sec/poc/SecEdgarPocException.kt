package sec.poc

/**
 * SEC EDGAR submissions PoC の Fail-Closed 例外。
 * mock / synthetic へのフォールバックは行わない。
 */
class SecEdgarPocException(message: String, cause: Throwable? = null) : IllegalStateException(message, cause)
