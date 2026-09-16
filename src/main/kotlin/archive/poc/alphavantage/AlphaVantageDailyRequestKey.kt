package archive.poc.alphavantage

/**
 * Strict parse of Alpha Vantage TIME_SERIES_DAILY archive [requestKey].
 *
 * Canonical form only:
 * `GET|/query|function=TIME_SERIES_DAILY|symbol={SYMBOL}|outputsize={compact|full}`
 *
 * Fail-Closed on any deviation. Never invents a symbol from free-form caller input.
 */
object AlphaVantageDailyRequestKey {
    data class Parsed(
        val symbol: String,
        val outputSize: String,
    )

    fun parseOrThrow(requestKey: String): Parsed {
        val parts = requestKey.split('|')
        require(parts.size == 5) {
            "malformed Alpha Vantage requestKey: expected 5 pipe-separated segments"
        }
        require(parts[0] == "GET") { "malformed Alpha Vantage requestKey: method must be GET" }
        require(parts[1] == AlphaVantageDailyArchiveClient.QUERY_PATH) {
            "malformed Alpha Vantage requestKey: path must be ${AlphaVantageDailyArchiveClient.QUERY_PATH}"
        }
        require(parts[2] == "function=${AlphaVantageDailyArchiveClient.FUNCTION}") {
            "malformed Alpha Vantage requestKey: function must be ${AlphaVantageDailyArchiveClient.FUNCTION}"
        }
        require(parts[3].startsWith("symbol=")) {
            "malformed Alpha Vantage requestKey: missing symbol="
        }
        val symbol = parts[3].removePrefix("symbol=")
        require(symbol.isNotBlank()) { "malformed Alpha Vantage requestKey: blank symbol" }
        require(!symbol.contains('|') && symbol == symbol.trim()) {
            "malformed Alpha Vantage requestKey: illegal symbol"
        }
        require(parts[4].startsWith("outputsize=")) {
            "malformed Alpha Vantage requestKey: missing outputsize="
        }
        val outputSize = parts[4].removePrefix("outputsize=")
        require(outputSize == "compact" || outputSize == "full") {
            "malformed Alpha Vantage requestKey: outputsize must be compact|full"
        }
        val canonical = AlphaVantageDailyArchiveClient.requestKey(symbol, outputSize)
        require(canonical == requestKey) {
            "malformed Alpha Vantage requestKey: not canonical (got=$requestKey expected=$canonical)"
        }
        return Parsed(symbol = symbol, outputSize = outputSize)
    }
}
