package sec.poc

import java.time.Duration

/**
 * Live SEC XBRL CompanyFacts PoC.
 *
 * 環境変数 SEC_EDGAR_USER_AGENT 必須。未設定なら Fail-Closed。
 * mock / synthetic fallback なし。SecurityId へ自動割当しない。
 *
 * 実行例:
 * SEC_EDGAR_USER_AGENT='USStockInvestmentAssistant PoC you@domain' \
 *   ./gradlew --no-daemon secCompanyFactsPoc
 */
fun main() {
    val config =
        SecEdgarPocConfig.fromEnvironment().copy(
            requestTimeout = Duration.ofSeconds(90),
            minIntervalBetweenRequests = Duration.ofMillis(1100),
        )
    val factsClient = SecCompanyFactsPocClient(config)
    val submissionsClient = SecEdgarSubmissionsPocClient(config)
    val artifactClient = SecFilingArtifactClient(config)

    val targets =
        listOf(
            SecCik.parse("0000320193") to "Apple Inc.",
            SecCik.parse("0000789019") to "Microsoft Corp",
            SecCik.parse("0001652044") to "Alphabet Inc.",
        )

    println("SEC EDGAR XBRL CompanyFacts PoC")
    println("endpointTemplate=https://data.sec.gov/api/xbrl/companyfacts/CIK##########.json")
    println("userAgentConfigured=true")
    println("securityIdAutoAssign=false")
    println("issuerIdImplemented=false")
    println("knownAtAssessments:")
    SecCompanyFactsParser.assessKnownAt().forEach { (field, grade) ->
        println("- $field: $grade")
    }

    val appleJoins = mutableListOf<SecCompanyFactsAccessionJoin>()

    for ((cik, label) in targets) {
        println()
        println("=== $label CIK=${cik.value} ===")
        val result = factsClient.fetchCompanyFacts(cik)
        val doc = result.document
        val ev = result.evidence
        println("endpoint=${ev.endpoint}")
        println("httpStatus=${ev.httpStatus}")
        println("fetchedAt=${ev.fetchedAt} // post parse+CIK match; not historical knownAt")
        println("payloadBytes=${ev.payloadBytes}")
        println("payloadSha256=${ev.payloadSha256}")
        println("entityName=${doc.entityName}")
        println("payloadCik=${doc.cik.value}")
        println("taxonomies=${doc.taxonomies.sorted()}")
        println("retainedConcepts=${doc.concepts.size}")
        println("observedTopLevelKeys=${doc.observedTopLevelKeys.sorted()}")
        println("mapsToSecurityId=false // CompanyFacts CIK is Issuer/filing-entity side")

        for (concept in doc.concepts.sortedBy { it.tag }) {
            val unitSummary =
                concept.units.entries.joinToString("; ") { (u, vs) -> "$u=${vs.size}" }
            println(
                "concept taxonomy=${concept.taxonomy} tag=${concept.tag} " +
                    "label=${concept.label} units=[$unitSummary]",
            )
        }

        val netIncome =
            doc.concepts.find { it.tag == "NetIncomeLoss" }
                ?: continue
        val usd = netIncome.units["USD"].orEmpty()
        printDuplicateGroups(usd)

        if (cik.value == "0000320193") {
            val submissions = submissionsClient.fetchSubmissions(cik)
            println("submissionsRecent=${submissions.document.recentFilings.size}")
            val picks = pickJoinFacts(usd)
            for (fact in picks) {
                val join =
                    SecCompanyFactsAccessionJoiner.joinToArchiveAndSubmissions(
                        fact = fact,
                        issuerCik = cik,
                        submissions = submissions.document,
                        artifactClient = artifactClient,
                    )
                appleJoins += join
                println(
                    "JOIN form=${fact.form} fy=${fact.fy} fp=${fact.fp} end=${fact.end} " +
                        "filed=${fact.filed} frame=${fact.frame} accn=${fact.accn?.value} " +
                        "inSubmissionsRecent=${join.foundInSubmissionsRecent} " +
                        "archiveIndexStatus=${join.archiveIndexHttpStatus} " +
                        "archiveUrl=${join.archiveIndexUrl}",
                )
            }
        }
    }

    println()
    println("=== accession join summary (Apple) ===")
    println("joinCount=${appleJoins.size}")
    appleJoins.forEach {
        println(
            "- ${it.fact.form} ${it.accession.value} recent=${it.foundInSubmissionsRecent} " +
                "archiveHttp=${it.archiveIndexHttpStatus}",
        )
    }
    println("conclusion_knownAt=CompanyFacts.filed alone is UNUSABLE as historical knownAt; accession join required and still not CONFIRMED")
    println("conclusion_securityId=Do not assign CompanyFacts directly to SecurityId")
}

private fun pickJoinFacts(versions: List<SecCompanyFactVersion>): List<SecCompanyFactVersion> {
    val out = mutableListOf<SecCompanyFactVersion>()
    for (form in listOf("10-K", "10-Q", "10-K/A")) {
        val hit =
            versions.asReversed().firstOrNull { it.form == form && it.accn != null }
                ?: continue
        out += hit
    }
    return out
}

private fun printDuplicateGroups(versions: List<SecCompanyFactVersion>) {
    val groups =
        versions
            .groupBy { Triple(it.end, it.fy, it.fp) }
            .filter { (_, vs) -> vs.map { it.accn?.value }.toSet().size > 1 }
    println("duplicatePeriodMultiAccessionGroups=${groups.size}")
    groups.entries.take(3).forEach { (key, vs) ->
        println(" duplicateKey end/fy/fp=$key candidates=${vs.size}")
        vs.forEach { v ->
            println(
                "  candidate val=${v.value} form=${v.form} filed=${v.filed} " +
                    "frame=${v.frame} accn=${v.accn?.value} unit=${v.unit}",
            )
        }
    }
}
