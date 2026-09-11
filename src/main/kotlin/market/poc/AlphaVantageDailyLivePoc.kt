package market.poc

/**
 * Live Alpha Vantage TIME_SERIES_DAILY PoC.
 *
 * Uses ALPHAVANTAGE_API_KEY when set; otherwise demo key.
 * Never prints the API key. Never invents historical knownAt. Never assigns SecurityId.
 */
fun main() {
    val keyPresent = !System.getenv(AlphaVantageDailyPocClient.ENV_API_KEY).isNullOrBlank()
    val client = AlphaVantageDailyPocClient.fromEnvironment()

    println("provider=Alpha Vantage")
    println("function=${AlphaVantageDailyPocClient.FUNCTION}")
    println("apiKeyPresent=$keyPresent")
    println("apiKeySource=${if (keyPresent) ApiKeySource.ENVIRONMENT else ApiKeySource.DEMO}")
    println("historicalKnownAtPolicy=UNRESOLVED_UNUSABLE_without_row_publication_timestamp")
    println("securityIdMapping=forbidden")
    println("dailyPriceMapping=forbidden_without_historical_knownAt")

    val symbols =
        if (keyPresent) {
            listOf("AAPL", "MSFT", "GOOGL")
        } else {
            listOf("IBM")
        }

    for (symbol in symbols) {
        println("=== live symbol=$symbol ===")
        try {
            val result = client.fetchDailySeries(symbol)
            val series = result.series
            val evidence = result.evidence
            println("httpStatus=${evidence.httpStatus}")
            println("endpoint=${evidence.endpoint}")
            println("payloadBytes=${evidence.payloadBytes}")
            println("payloadSha256=${evidence.payloadSha256}")
            println("fetchedAt=${evidence.fetchedAt}")
            println("providerSymbol=${series.providerSymbol}")
            println("timeZoneRaw=${series.timeZoneRaw}")
            println("lastRefreshedRaw=${series.lastRefreshedRaw}")
            println("outputSizeRaw=${series.outputSizeRaw}")
            println("barCount=${series.bars.size}")
            println("firstTradingDate=${series.bars.first().tradingDate}")
            println("lastTradingDate=${series.bars.last().tradingDate}")
            val sample = series.bars.last()
            println(
                "sampleOHLCV=${sample.tradingDate} o=${sample.open} h=${sample.high} " +
                    "l=${sample.low} c=${sample.close} v=${sample.volume}",
            )
            println("historicalKnownAtStatus=${series.historicalKnownAtStatus}")
            println("mappedToDailyPrice=false")
            println("mappedToSecurityId=false")
            println("status=FETCH_PARSE_OK")
        } catch (e: AlphaVantagePocException) {
            println("status=FAIL_CLOSED")
            println("error=${e.message}")
        }
    }

    if (!keyPresent) {
        println(
            "NOTE: ALPHAVANTAGE_API_KEY absent. Demo-only live attempt does not prove " +
                "AAPL/MSFT/GOOGL availability or US-equity provider readiness.",
        )
    }
}
