package dividend.poc

/**
 * Live Alpha Vantage DIVIDENDS PoC.
 *
 * Uses ALPHAVANTAGE_API_KEY when set; otherwise demo key.
 * Never prints the API key. Never invents historical knownAt / currency / REGULAR.
 * Never assigns SecurityId. Never maps to DividendEvent.
 */
fun main() {
    val keyPresent = !System.getenv(AlphaVantageDividendPocClient.ENV_API_KEY).isNullOrBlank()
    val client = AlphaVantageDividendPocClient.fromEnvironment()

    println("provider=Alpha Vantage")
    println("function=${AlphaVantageDividendPocClient.FUNCTION}")
    println("apiKeyPresent=$keyPresent")
    println("apiKeySource=${if (keyPresent) ApiKeySource.ENVIRONMENT else ApiKeySource.DEMO}")
    println("historicalKnownAtPolicy=UNRESOLVED_UNUSABLE_declarationDate_is_not_knownAt")
    println("currencyPolicy=UNRESOLVED_FROM_DIVIDENDS_no_USD_inference")
    println("dividendTypePolicy=UNRESOLVED_FROM_DIVIDENDS_no_REGULAR_default")
    println("securityIdMapping=forbidden")
    println("dividendEventMapping=forbidden_without_knownAt_currency_securityId_type_evidence")

    val symbols =
        if (keyPresent) {
            listOf("AAPL", "MSFT", "GOOGL")
        } else {
            listOf("IBM")
        }

    for (symbol in symbols) {
        println("=== live symbol=$symbol ===")
        try {
            val result = client.fetchDividendSeries(symbol)
            val series = result.series
            val evidence = result.evidence
            println("httpStatus=${evidence.httpStatus}")
            println("endpoint=${evidence.endpoint}")
            println("payloadBytes=${evidence.payloadBytes}")
            println("payloadSha256=${evidence.payloadSha256}")
            println("fetchedAt=${evidence.fetchedAt}")
            println("providerSymbol=${series.providerSymbol}")
            println("eventCount=${series.events.size}")
            if (series.events.isNotEmpty()) {
                val newest = series.events.first()
                val oldest = series.events.last()
                println("newestExDividendDate=${newest.exDividendDate}")
                println("oldestExDividendDate=${oldest.exDividendDate}")
                println(
                    "sampleEvent=ex=${newest.exDividendDate} declaration=${newest.declarationDate} " +
                        "record=${newest.recordDate} payment=${newest.paymentDate} amount=${newest.amount}",
                )
                val absentOptional =
                    series.events.count {
                        it.declarationDate == null || it.recordDate == null || it.paymentDate == null
                    }
                println("eventsWithAbsentOptionalDates=$absentOptional")
                val futureDeclared =
                    series.events.count {
                        it.exDividendDate.isAfter(java.time.LocalDate.now(java.time.ZoneOffset.UTC))
                    }
                println("eventsWithExAfterUtcToday=$futureDeclared")
            }
            println("historicalKnownAtStatus=${series.historicalKnownAtStatus}")
            println("currencyResolutionStatus=${series.currencyResolutionStatus}")
            println("dividendTypeResolutionStatus=${series.dividendTypeResolutionStatus}")
            println("securityIdMappingStatus=${series.securityIdMappingStatus}")
            println("mappedToDividendEvent=false")
            println("mappedToSecurityId=false")
            println("currencyInferred=false")
            println("dividendTypeInferred=false")
            println("declarationDateUsedAsKnownAt=false")
            println("status=FETCH_PARSE_OK")
        } catch (e: AlphaVantageDividendPocException) {
            println("status=FAIL_CLOSED")
            println("error=${e.message}")
        }
    }

    if (!keyPresent) {
        println(
            "NOTE: ALPHAVANTAGE_API_KEY absent. Demo-only live attempt does not prove " +
                "AAPL/MSFT/GOOGL availability or US-equity dividend provider readiness.",
        )
    }
}
