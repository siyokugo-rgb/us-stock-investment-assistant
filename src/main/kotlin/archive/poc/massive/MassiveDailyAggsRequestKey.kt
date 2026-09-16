package archive.poc.massive

/**
 * Strict parse of Massive Custom Bars 1-day unadjusted archive [requestKey].
 *
 * Canonical form only:
 * `GET|/v2/aggs/ticker/{TICKER}/range/1/day/{FROM}/{TO}|adjusted=false|sort=asc|limit={LIMIT}`
 *
 * Fail-Closed on any deviation. Never invents a ticker from free-form caller input.
 * API key must never appear.
 */
object MassiveDailyAggsRequestKey {
    data class Parsed(
        val ticker: String,
        val from: String,
        val to: String,
        val limit: Int,
    )

    fun parseOrThrow(requestKey: String): Parsed {
        val parts = requestKey.split('|')
        require(parts.size == 5) {
            "malformed Massive requestKey: expected 5 pipe-separated segments"
        }
        require(parts[0] == "GET") { "malformed Massive requestKey: method must be GET" }
        val path = parts[1]
        val pathRegex =
            Regex(
                "^${Regex.escape(MassiveDailyAggsArchiveClient.PATH_PREFIX)}" +
                    "([^/]+)" +
                    Regex.escape(MassiveDailyAggsArchiveClient.PATH_RANGE_SUFFIX) +
                    "([^/]+)/([^/]+)$",
            )
        val m =
            pathRegex.matchEntire(path)
                ?: throw IllegalArgumentException(
                    "malformed Massive requestKey: path must be " +
                        "${MassiveDailyAggsArchiveClient.PATH_PREFIX}{ticker}" +
                        "${MassiveDailyAggsArchiveClient.PATH_RANGE_SUFFIX}{from}/{to}",
                )
        val ticker = m.groupValues[1]
        val from = m.groupValues[2]
        val to = m.groupValues[3]
        require(ticker.isNotBlank()) { "malformed Massive requestKey: blank ticker" }
        require(!ticker.contains('|') && ticker == ticker.trim()) {
            "malformed Massive requestKey: illegal ticker"
        }
        require(DATE_OR_MILLIS.matches(from)) { "malformed Massive requestKey: illegal from" }
        require(DATE_OR_MILLIS.matches(to)) { "malformed Massive requestKey: illegal to" }
        require(parts[2] == "adjusted=false") {
            "malformed Massive requestKey: adjusted must be false"
        }
        require(parts[3] == "sort=asc") { "malformed Massive requestKey: sort must be asc" }
        require(parts[4].startsWith("limit=")) {
            "malformed Massive requestKey: missing limit="
        }
        val limitRaw = parts[4].removePrefix("limit=")
        val limit =
            limitRaw.toIntOrNull()
                ?: throw IllegalArgumentException("malformed Massive requestKey: limit not int")
        require(limit > 0) { "malformed Massive requestKey: limit must be positive" }
        val canonical = MassiveDailyAggsArchiveClient.requestKey(ticker, from, to, limit)
        require(canonical == requestKey) {
            "malformed Massive requestKey: not canonical (got=$requestKey expected=$canonical)"
        }
        return Parsed(ticker = ticker, from = from, to = to, limit = limit)
    }

    private val DATE_OR_MILLIS = Regex("^(\\d{4}-\\d{2}-\\d{2}|\\d+)$")
}
