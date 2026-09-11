package sec.poc

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

/**
 * Accession 単位で filing index / primary / complete submission text を取得する最小 client。
 * 財務値解析はしない。失敗時に mock へ切り替えない。
 */
class SecFilingArtifactClient(
    private val config: SecEdgarPocConfig,
    private val wwwBaseUrl: String = SecEdgarArchivePaths.DEFAULT_WWW_BASE,
    private val httpClient: HttpClient =
        HttpClient.newBuilder()
            .connectTimeout(config.connectTimeout)
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build(),
    private val sleeper: (Long) -> Unit = { ms -> Thread.sleep(ms) },
    private val clock: () -> Instant = { Instant.now() },
) {
    private val lastRequestEpochMs = AtomicLong(0L)

    fun fetchBundle(meta: SecAccessionFilingMeta): SecAccessionArtifactBundle {
        val index =
            fetchArtifact(
                expectedAccession = meta.accessionNumber,
                artifactType = SecArtifactType.FILING_INDEX,
                url = SecEdgarArchivePaths.filingIndexHtml(meta.issuerCik, meta.accessionNumber, wwwBaseUrl),
                source = "sec.gov Archives filing index",
            )
        val primary =
            fetchArtifact(
                expectedAccession = meta.accessionNumber,
                artifactType = SecArtifactType.PRIMARY_DOCUMENT,
                url =
                    SecEdgarArchivePaths.primaryDocument(
                        meta.issuerCik,
                        meta.accessionNumber,
                        meta.primaryDocument,
                        wwwBaseUrl,
                    ),
                source = "sec.gov Archives primary document",
            )
        val complete =
            fetchArtifact(
                expectedAccession = meta.accessionNumber,
                artifactType = SecArtifactType.COMPLETE_SUBMISSION_TEXT,
                url =
                    SecEdgarArchivePaths.completeSubmissionText(
                        meta.issuerCik,
                        meta.accessionNumber,
                        wwwBaseUrl,
                    ),
                source = "sec.gov Archives complete submission text",
            )
        return SecAccessionArtifactBundle(
            meta = meta,
            filingIndex = index,
            primaryDocument = primary,
            completeSubmissionText = complete,
        )
    }

    fun fetchArtifact(
        expectedAccession: SecAccessionNumber,
        artifactType: SecArtifactType,
        url: String,
        source: String,
    ): SecFetchedArtifact {
        throttle()
        val request =
            HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(config.requestTimeout)
                .header("User-Agent", config.userAgent)
                .header("Accept", "*/*")
                .GET()
                .build()

        val response =
            try {
                httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray())
            } catch (e: Exception) {
                throw SecEdgarPocException("SEC artifact HTTP call failed for $url: ${e.message}", e)
            }

        val status = response.statusCode()
        val bodyBytes = response.body() ?: ByteArray(0)
        val contentType = response.headers().firstValue("Content-Type").orElse(null)

        if (status in 400..599) {
            throw SecEdgarPocException(
                "SEC HTTP $status for $url (Fail-Closed; no mock fallback). bodyBytes=${bodyBytes.size}",
            )
        }
        if (status !in 200..299) {
            throw SecEdgarPocException("Unexpected SEC HTTP $status for $url")
        }
        if (bodyBytes.isEmpty()) {
            throw SecEdgarPocException("Empty SEC artifact body for $url")
        }

        assertAccessionConsistency(expectedAccession, artifactType, bodyBytes, url)

        val sha = SecEdgarSubmissionsPocClient.sha256Hex(bodyBytes)
        // fetchedAt / ingestedAt: only after HTTP success, body held, and accession consistency OK.
        val fetchedAt = clock()

        return SecFetchedArtifact(
            provenance =
                SecArtifactProvenance(
                    provider = "SEC",
                    accessionNumber = expectedAccession,
                    artifactType = artifactType,
                    endpoint = url,
                    httpStatus = status,
                    contentType = contentType,
                    payloadBytes = bodyBytes.size,
                    payloadSha256 = sha,
                    fetchedAt = fetchedAt,
                    source = source,
                ),
            body = bodyBytes,
        )
    }

    private fun assertAccessionConsistency(
        expected: SecAccessionNumber,
        artifactType: SecArtifactType,
        body: ByteArray,
        url: String,
    ) {
        val text = String(body, StandardCharsets.UTF_8)
        when (artifactType) {
            SecArtifactType.FILING_INDEX -> {
                if (!text.contains(expected.value)) {
                    throw SecEdgarPocException(
                        "Accession mismatch in filing index: expected ${expected.value} at $url",
                    )
                }
            }
            SecArtifactType.COMPLETE_SUBMISSION_TEXT -> {
                val headerAccession =
                    extractAccessionFromCompleteSubmission(text)
                        ?: throw SecEdgarPocException(
                            "ACCESSION NUMBER not found in complete submission text at $url",
                        )
                if (headerAccession != expected.value) {
                    throw SecEdgarPocException(
                        "Accession mismatch: requested ${expected.value}, artifact $headerAccession ($url)",
                    )
                }
            }
            SecArtifactType.PRIMARY_DOCUMENT -> {
                // Primary HTML may omit accession; amendments may cite a prior accession.
                // Identity is bound by URL under the accession directory; index + .txt enforce consistency.
            }
        }
    }

    private fun throttle() {
        val minInterval = config.minIntervalBetweenRequests.toMillis().coerceAtLeast(0L)
        if (minInterval == 0L) return
        while (true) {
            val now = System.currentTimeMillis()
            val prev = lastRequestEpochMs.get()
            val wait = minInterval - (now - prev)
            if (prev == 0L || wait <= 0L) {
                if (lastRequestEpochMs.compareAndSet(prev, now)) return
            } else {
                sleeper(wait)
            }
        }
    }

    companion object {
        private val ACCESSION_HEADER =
            Regex("""(?im)^ACCESSION NUMBER:\s*(\d{10}-\d{2}-\d{6})\s*$""")

        fun extractAccessionFromCompleteSubmission(text: String): String? =
            ACCESSION_HEADER.find(text)?.groupValues?.get(1)
    }
}

object SecAmendmentRelationshipAssessor {
    private val HUMAN_DATE: DateTimeFormatter =
        DateTimeFormatter.ofPattern("MMMM d, yyyy", Locale.US)

    /**
     * form+/A と reportDate 一致だけでは CONFIRMED にしない。
     * 本文に相手 accession が明示されていれば CONFIRMED。
     * 日付参照など間接証拠のみなら LIKELY。
     * それ以外は UNVERIFIED。
     */
    fun assess(
        original: SecAccessionFilingMeta,
        amendment: SecAccessionFilingMeta,
        amendmentPrimaryText: String,
        amendmentCompleteText: String,
    ): SecAmendmentRelationshipAssessment {
        val notes = mutableListOf<String>()
        if (!amendment.form.endsWith("/A")) {
            notes.add("Candidate amendment form '${amendment.form}' does not end with /A")
        }
        if (original.form + "/A" != amendment.form &&
            !(original.form == "8-K" && amendment.form == "8-K/A")
        ) {
            notes.add("Form pair is not a direct base+/A pair: ${original.form} vs ${amendment.form}")
        }
        if (original.reportDate != null &&
            amendment.reportDate != null &&
            original.reportDate == amendment.reportDate
        ) {
            notes.add("Same reportDate=${original.reportDate} (insufficient alone for CONFIRMED)")
        } else {
            notes.add(
                "reportDate original=${original.reportDate} amendment=${amendment.reportDate} (not equal or missing)",
            )
        }

        val corpus = amendmentPrimaryText + "\n" + amendmentCompleteText
        if (corpus.contains(original.accessionNumber.value)) {
            notes.add(
                "Amendment text explicitly cites original accession ${original.accessionNumber.value}",
            )
            return SecAmendmentRelationshipAssessment(
                originalAccession = original.accessionNumber,
                amendmentAccession = amendment.accessionNumber,
                grade = SecAmendmentRelationshipGrade.CONFIRMED,
                evidenceNotes = notes,
            )
        }
        notes.add("Amendment text does not cite original accession ${original.accessionNumber.value}")

        val likelySignals = mutableListOf<String>()
        if (original.filingDate != null) {
            val iso = original.filingDate.toString()
            if (corpus.contains(iso)) {
                likelySignals.add("Amendment text contains original filingDate $iso")
            }
            val human = original.filingDate.format(HUMAN_DATE)
            if (corpus.contains(human)) {
                likelySignals.add("Amendment text contains original filingDate phrase '$human'")
            }
        }
        if (corpus.contains("Original Form", ignoreCase = true) ||
            corpus.contains("amends", ignoreCase = true) ||
            corpus.contains("Amendment to the Original", ignoreCase = true)
        ) {
            likelySignals.add("Amendment text uses original/amends wording")
        }

        notes.addAll(likelySignals)
        val grade =
            if (likelySignals.isNotEmpty() &&
                original.reportDate != null &&
                original.reportDate == amendment.reportDate &&
                original.form == "8-K" &&
                amendment.form == "8-K/A"
            ) {
                SecAmendmentRelationshipGrade.LIKELY
            } else {
                SecAmendmentRelationshipGrade.UNVERIFIED
            }

        return SecAmendmentRelationshipAssessment(
            originalAccession = original.accessionNumber,
            amendmentAccession = amendment.accessionNumber,
            grade = grade,
            evidenceNotes = notes,
        )
    }
}
