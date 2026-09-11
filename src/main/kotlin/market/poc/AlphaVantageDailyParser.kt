package market.poc

import java.math.BigDecimal
import java.security.MessageDigest
import java.time.LocalDate
import java.time.format.DateTimeParseException

object AlphaVantageDailyParser {
    private const val META = "Meta Data"
    private const val SERIES = "Time Series (Daily)"
    private const val META_INFO = "1. Information"
    private const val META_SYMBOL = "2. Symbol"
    private const val META_REFRESHED = "3. Last Refreshed"
    private const val META_OUTPUT = "4. Output Size"
    private const val META_TZ = "5. Time Zone"
    private const val OPEN = "1. open"
    private const val HIGH = "2. high"
    private const val LOW = "3. low"
    private const val CLOSE = "4. close"
    private const val VOLUME = "5. volume"

    fun parse(
        jsonText: String,
        requestedSymbol: String,
    ): AlphaVantageDailyRawSeries {
        if (jsonText.isBlank()) {
            throw AlphaVantagePocException("Empty Alpha Vantage body")
        }
        val root =
            try {
                MarketPocJson.parse(jsonText).asObject("alphavantage")
            } catch (e: AlphaVantagePocException) {
                throw e
            } catch (e: Exception) {
                throw AlphaVantagePocException("Malformed Alpha Vantage JSON: ${e.message}", e)
            }

        rejectProviderEnvelope(root)

        val meta = root.required(META, "alphavantage").asObject(META)
        val seriesNode = root.required(SERIES, "alphavantage").asObject(SERIES)

        val symbol = meta.required(META_SYMBOL, META).asString(META_SYMBOL).trim()
        if (!symbol.equals(requestedSymbol.trim(), ignoreCase = true)) {
            throw AlphaVantagePocException(
                "Requested symbol mismatch: requested='$requestedSymbol', payload='$symbol'",
            )
        }

        val bars =
            seriesNode.map.entries
                .map { (dateRaw, barNode) ->
                    parseBar(
                        providerSymbol = symbol,
                        dateRaw = dateRaw,
                        node = barNode.asObject("$SERIES[$dateRaw]"),
                    )
                }.sortedBy { it.tradingDate }

        if (bars.isEmpty()) {
            throw AlphaVantagePocException("Time Series (Daily) is empty")
        }

        return AlphaVantageDailyRawSeries(
            providerSymbol = symbol,
            information = meta.optional(META_INFO)?.asString(META_INFO),
            lastRefreshedRaw = meta.optional(META_REFRESHED)?.asString(META_REFRESHED),
            outputSizeRaw = meta.optional(META_OUTPUT)?.asString(META_OUTPUT),
            timeZoneRaw = meta.optional(META_TZ)?.asString(META_TZ),
            bars = bars,
            historicalKnownAtStatus = HistoricalKnownAtStatus.UNRESOLVED_UNUSABLE,
        )
    }

    private fun rejectProviderEnvelope(root: MarketPocJson.Obj) {
        val error = root.optional("Error Message")?.asString("Error Message")
        if (!error.isNullOrBlank()) {
            throw AlphaVantagePocException("Alpha Vantage Error Message: $error")
        }
        val information = root.optional("Information")?.asString("Information")
        if (!information.isNullOrBlank()) {
            throw AlphaVantagePocException("Alpha Vantage Information envelope: $information")
        }
        val note = root.optional("Note")?.asString("Note")
        if (!note.isNullOrBlank()) {
            throw AlphaVantagePocException("Alpha Vantage Note envelope: $note")
        }
        if (!root.map.containsKey(META) || !root.map.containsKey(SERIES)) {
            throw AlphaVantagePocException(
                "Expected '$META' and '$SERIES' objects; keys=${root.map.keys}",
            )
        }
    }

    private fun parseBar(
        providerSymbol: String,
        dateRaw: String,
        node: MarketPocJson.Obj,
    ): AlphaVantageDailyRawBar {
        val tradingDate =
            try {
                LocalDate.parse(dateRaw)
            } catch (e: DateTimeParseException) {
                throw AlphaVantagePocException("Invalid tradingDate key '$dateRaw'", e)
            }
        val open = parseDecimal(node.required(OPEN, "bar").asString(OPEN), OPEN)
        val high = parseDecimal(node.required(HIGH, "bar").asString(HIGH), HIGH)
        val low = parseDecimal(node.required(LOW, "bar").asString(LOW), LOW)
        val close = parseDecimal(node.required(CLOSE, "bar").asString(CLOSE), CLOSE)
        val volume = parseLong(node.required(VOLUME, "bar").asString(VOLUME), VOLUME)
        return AlphaVantageDailyRawBar(
            providerSymbol = providerSymbol,
            tradingDate = tradingDate,
            open = open,
            high = high,
            low = low,
            close = close,
            volume = volume,
        )
    }

    private fun parseDecimal(
        raw: String,
        context: String,
    ): BigDecimal =
        try {
            BigDecimal(raw.trim())
        } catch (e: NumberFormatException) {
            throw AlphaVantagePocException("$context: invalid decimal '$raw'", e)
        }

    private fun parseLong(
        raw: String,
        context: String,
    ): Long =
        try {
            raw.trim().toLong()
        } catch (e: NumberFormatException) {
            throw AlphaVantagePocException("$context: invalid long '$raw'", e)
        }

    fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { b -> "%02x".format(b) }
    }
}
