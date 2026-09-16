package archive.poc.massive

/**
 * Strict parse of Massive Ticker Overview archive [requestKey].
 *
 * Canonical forms:
 * - `GET|/v3/reference/tickers/{TICKER}`
 * - `GET|/v3/reference/tickers/{TICKER}|date={YYYY-MM-DD}`
 *
 * Fail-Closed on any deviation. API key must never appear.
 */
object MassiveTickerOverviewRequestKey {
    data class Parsed(
        val ticker: String,
        val date: String?,
    )

    fun parseOrThrow(requestKey: String): Parsed {
        val parts = requestKey.split('|')
        require(parts.size == 2 || parts.size == 3) {
            "malformed Massive ticker-overview requestKey: expected 2 or 3 pipe-separated segments"
        }
        require(parts[0] == "GET") {
            "malformed Massive ticker-overview requestKey: method must be GET"
        }
        val path = parts[1]
        val pathRegex =
            Regex(
                "^${Regex.escape(MassiveTickerOverviewArchiveClient.PATH_PREFIX)}([^/]+)$",
            )
        val m =
            pathRegex.matchEntire(path)
                ?: throw IllegalArgumentException(
                    "malformed Massive ticker-overview requestKey: path must be " +
                        "${MassiveTickerOverviewArchiveClient.PATH_PREFIX}{ticker}",
                )
        val ticker = m.groupValues[1]
        require(ticker.isNotBlank()) { "malformed Massive ticker-overview requestKey: blank ticker" }
        require(!ticker.contains('|') && ticker == ticker.trim()) {
            "malformed Massive ticker-overview requestKey: illegal ticker"
        }
        val date =
            if (parts.size == 3) {
                require(parts[2].startsWith("date=")) {
                    "malformed Massive ticker-overview requestKey: expected date="
                }
                val raw = parts[2].removePrefix("date=")
                require(DATE.matches(raw)) {
                    "malformed Massive ticker-overview requestKey: illegal date"
                }
                raw
            } else {
                null
            }
        val canonical = MassiveTickerOverviewArchiveClient.requestKey(ticker, date)
        require(canonical == requestKey) {
            "malformed Massive ticker-overview requestKey: not canonical " +
                "(got=$requestKey expected=$canonical)"
        }
        return Parsed(ticker = ticker, date = date)
    }

    private val DATE = Regex("^\\d{4}-\\d{2}-\\d{2}$")
}
