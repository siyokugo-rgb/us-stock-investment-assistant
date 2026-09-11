package sec.poc

/**
 * Live SEC EDGAR submissions PoC runner.
 *
 * 環境変数 SEC_EDGAR_USER_AGENT 必須。未設定なら Fail-Closed。
 * mock / synthetic fallback は行わない。
 *
 * 実行例:
 * SEC_EDGAR_USER_AGENT='USStockInvestmentAssistant PoC you@domain' \
 *   ./gradlew --no-daemon secEdgarPoc
 */
fun main() {
    val config = SecEdgarPocConfig.fromEnvironment()
    val client = SecEdgarSubmissionsPocClient(config)

    // CIK は SEC company_tickers.json で確認済み。Ticker から推測していない。
    val targets =
        listOf(
            SecCik.parse("0000320193") to "Apple Inc.",
            SecCik.parse("0000789019") to "Microsoft Corp",
            SecCik.parse("0001652044") to "Alphabet Inc.",
        )

    println("SEC EDGAR submissions PoC")
    println("userAgentConfigured=true")
    println("timestampAssessments:")
    SecSubmissionsParser.assessTimestampFields().forEach { a ->
        println("- ${a.field}: ${a.gradeForHistoricalKnownAt} // ${a.meaning}")
    }

    for ((cik, label) in targets) {
        println()
        println("=== $label CIK=${cik.value} ===")
        val result = client.fetchSubmissions(cik)
        val doc = result.document
        val ev = result.evidence
        println("endpoint=${ev.endpoint}")
        println("httpStatus=${ev.httpStatus}")
        println("fetchedAt=${ev.fetchedAt}")
        println("payloadBytes=${ev.payloadBytes}")
        println("payloadSha256=${ev.payloadSha256}")
        println("name=${doc.name}")
        println("tickers=${doc.tickers}")
        println("exchanges=${doc.exchanges}")
        println("formerNames=${doc.formerNames.size}")
        println("recentFilings=${doc.recentFilings.size}")
        println("historyFiles=${doc.historyFiles}")
        println("observedTopLevelKeys=${doc.observedTopLevelKeys.sorted()}")
        println("observedRecentKeys=${doc.observedRecentKeys.sorted()}")

        val formsOfInterest = listOf("10-K", "10-Q", "8-K", "10-K/A", "10-Q/A", "8-K/A")
        for (form in formsOfInterest) {
            val hits = doc.recentFilings.filter { it.form == form }
            println("form[$form]=${hits.size}")
            hits.firstOrNull()?.let { f ->
                println(
                    "  sample accession=${f.accessionNumber} filingDate=${f.filingDate} " +
                        "reportDate=${f.reportDate} acceptance=${f.acceptanceDateTimeRaw}",
                )
            }
        }

        val amendments = doc.recentFilings.filter { it.isAmendment }
        println("amendmentRowsInRecent=${amendments.size}")
        amendments.take(5).forEach { f ->
            println(
                "  amendment form=${f.form} accession=${f.accessionNumber} " +
                    "acceptance=${f.acceptanceDateTimeRaw}",
            )
        }

        if (doc.tickers.size > 1) {
            println("MULTI_SHARE_CLASS_OR_MULTI_TICKER_UNDER_SAME_CIK=true")
        }
    }
}
