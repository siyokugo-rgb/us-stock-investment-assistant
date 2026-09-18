package archive.poc.massive

/**
 * Strict parse of Massive Ticker Events archive [requestKey].
 *
 * Canonical form only:
 * `GET|/vX/reference/tickers/{LOOKUP_ID}/events|types=ticker_change`
 *
 * Fail-Closed on any deviation. API key must never appear.
 */
object MassiveTickerEventsRequestKey {
    data class Parsed(
        val lookupId: String,
    )

    fun parseOrThrow(requestKey: String): Parsed {
        val parts = requestKey.split('|')
        require(parts.size == 3) {
            "malformed Massive ticker-events requestKey: expected exactly 3 pipe-separated segments"
        }
        require(parts[0] == "GET") {
            "malformed Massive ticker-events requestKey: method must be GET"
        }
        val path = parts[1]
        val pathRegex =
            Regex(
                "^${Regex.escape(MassiveTickerEventsArchiveClient.PATH_PREFIX)}" +
                    "([^/]+)" +
                    "${Regex.escape(MassiveTickerEventsArchiveClient.PATH_SUFFIX)}$",
            )
        val m =
            pathRegex.matchEntire(path)
                ?: throw IllegalArgumentException(
                    "malformed Massive ticker-events requestKey: path must be " +
                        "${MassiveTickerEventsArchiveClient.PATH_PREFIX}{id}" +
                        MassiveTickerEventsArchiveClient.PATH_SUFFIX,
                )
        val lookupId = m.groupValues[1]
        MassiveTickerEventsArchiveClient.normalizeLookupIdOrThrow(lookupId)

        require(parts[2] == "types=${MassiveTickerEventsArchiveClient.TYPES_TICKER_CHANGE}") {
            "malformed Massive ticker-events requestKey: expected " +
                "types=${MassiveTickerEventsArchiveClient.TYPES_TICKER_CHANGE}"
        }
        require(!requestKey.contains("apiKey", ignoreCase = true)) {
            "malformed Massive ticker-events requestKey: apiKey segment forbidden"
        }

        val canonical = MassiveTickerEventsArchiveClient.requestKey(lookupId)
        require(canonical == requestKey) {
            "malformed Massive ticker-events requestKey: not canonical " +
                "(got=$requestKey expected=$canonical)"
        }
        return Parsed(lookupId = lookupId)
    }
}
