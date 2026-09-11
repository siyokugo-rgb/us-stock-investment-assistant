package sec.poc

/**
 * Live SEC EDGAR accession / archive artifact PoC.
 *
 * 環境変数 SEC_EDGAR_USER_AGENT 必須。未設定なら Fail-Closed。
 * mock / synthetic fallback は行わない。
 *
 * 実行例:
 * SEC_EDGAR_USER_AGENT='USStockInvestmentAssistant PoC you@domain' \
 *   ./gradlew --no-daemon secEdgarAccessionPoc
 */
fun main() {
    val config = SecEdgarPocConfig.fromEnvironment()
    val submissionsClient = SecEdgarSubmissionsPocClient(config)
    val artifactClient = SecFilingArtifactClient(config)
    val store = SecAccessionArtifactStore()

    val issuerCik = SecCik.parse("0000320193")
    println("SEC EDGAR accession artifact PoC")
    println("issuerCik=${issuerCik.value} (Apple Inc.)")
    println("userAgentConfigured=true")
    println("archivesBase=${SecEdgarArchivePaths.DEFAULT_WWW_BASE}")

    val submissions = submissionsClient.fetchSubmissions(issuerCik)
    println("submissionsFetchedAt=${submissions.evidence.fetchedAt}")
    println("submissionsSha256=${submissions.evidence.payloadSha256}")

    // Live-selected pair: same reportDate 2026-04-17, forms 8-K and 8-K/A.
    // Relationship is assessed from filing text; not assumed from form/reportDate alone.
    val originalMeta =
        requireFiling(
            submissions.document,
            issuerCik,
            accession = "0001140361-26-015711",
            expectedForm = "8-K",
        )
    val amendmentMeta =
        requireFiling(
            submissions.document,
            issuerCik,
            accession = "0001140361-26-035325",
            expectedForm = "8-K/A",
        )

    println()
    println("=== candidate pair (same reportDate alone is NOT proof) ===")
    printMeta("originalCandidate", originalMeta)
    printMeta("amendmentCandidate", amendmentMeta)
    println(
        "submittingLoginCik(original)=${originalMeta.accessionNumber.submittingEntityCik.value} " +
            "(issuerRegistrantCik=${issuerCik.value}; equal=${originalMeta.accessionNumber.submittingEntityCik == issuerCik}; " +
            "prefix may be third-party filing agent)",
    )
    println(
        "submittingLoginCik(amendment)=${amendmentMeta.accessionNumber.submittingEntityCik.value} " +
            "(issuerRegistrantCik=${issuerCik.value}; equal=${amendmentMeta.accessionNumber.submittingEntityCik == issuerCik}; " +
            "prefix may be third-party filing agent)",
    )

    val originalBundle = artifactClient.fetchBundle(originalMeta)
    store.put(originalBundle)
    val amendmentBundle = artifactClient.fetchBundle(amendmentMeta)
    store.put(amendmentBundle)

    println()
    println("=== artifacts original ${originalMeta.accessionNumber.value} ===")
    printArtifact(originalBundle.filingIndex)
    printArtifact(originalBundle.primaryDocument)
    printArtifact(originalBundle.completeSubmissionText)

    println()
    println("=== artifacts amendment ${amendmentMeta.accessionNumber.value} ===")
    printArtifact(amendmentBundle.filingIndex)
    printArtifact(amendmentBundle.primaryDocument)
    printArtifact(amendmentBundle.completeSubmissionText)

    val relationship =
        SecAmendmentRelationshipAssessor.assess(
            original = originalMeta,
            amendment = amendmentMeta,
            amendmentPrimaryText = String(amendmentBundle.primaryDocument.body, Charsets.UTF_8),
            amendmentCompleteText = String(amendmentBundle.completeSubmissionText.body, Charsets.UTF_8),
        )
    println()
    println("=== relationship assessment ===")
    println("grade=${relationship.grade}")
    relationship.evidenceNotes.forEach { println("- $it") }
    println("storeSize=${store.size()} (must be 2; amendment must not overwrite original)")
    println("originalStillPresent=${store.get(originalMeta.accessionNumber) != null}")
    println("amendmentPresent=${store.get(amendmentMeta.accessionNumber) != null}")
    println("knownAtClaim=NONE (this PoC does not promote acceptanceDateTime to CONFIRMED knownAt)")
}

private fun requireFiling(
    document: SecSubmissionsDocument,
    issuerCik: SecCik,
    accession: String,
    expectedForm: String,
): SecAccessionFilingMeta {
    val acc = SecAccessionNumber.parse(accession)
    val row =
        document.recentFilings.find { it.accessionNumber == acc.value }
            ?: throw SecEdgarPocException(
                "Accession $accession not found in submissions.recent for ${issuerCik.value}",
            )
    if (row.form != expectedForm) {
        throw SecEdgarPocException("Expected form $expectedForm for $accession but was ${row.form}")
    }
    val primary =
        row.primaryDocument
            ?: throw SecEdgarPocException("primaryDocument missing for accession $accession")
    return SecAccessionFilingMeta(
        issuerCik = issuerCik,
        accessionNumber = acc,
        form = row.form,
        filingDate = row.filingDate,
        reportDate = row.reportDate,
        acceptanceDateTime = row.acceptanceDateTime,
        acceptanceDateTimeRaw = row.acceptanceDateTimeRaw,
        primaryDocument = primary,
        kind = if (row.isAmendment) SecSubmissionKind.AMENDMENT else SecSubmissionKind.ORIGINAL,
    )
}

private fun printMeta(
    label: String,
    meta: SecAccessionFilingMeta,
) {
    println(
        "$label accession=${meta.accessionNumber.value} form=${meta.form} " +
            "filingDate=${meta.filingDate} reportDate=${meta.reportDate} " +
            "acceptance=${meta.acceptanceDateTimeRaw} primary=${meta.primaryDocument} kind=${meta.kind}",
    )
}

private fun printArtifact(artifact: SecFetchedArtifact) {
    val p = artifact.provenance
    println(
        "artifactType=${p.artifactType} url=${p.endpoint} httpStatus=${p.httpStatus} " +
            "contentType=${p.contentType} bytes=${p.payloadBytes} sha256=${p.payloadSha256} " +
            "fetchedAt=${p.fetchedAt} accession=${p.accessionNumber.value} " +
            "source=${p.source} provider=${p.provider}",
    )
}
