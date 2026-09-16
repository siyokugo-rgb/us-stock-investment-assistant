package archive.poc.binding

import archive.poc.ArchiveValidationException
import archive.poc.ManifestRecord
import archive.poc.ObservationStatus
import archive.poc.Sha256Hex
import archive.poc.TransportStatus
import archive.poc.alphavantage.AlphaVantageDailyArchiveClient
import archive.poc.alphavantage.AlphaVantageDailyForwardArchiveService
import archive.poc.alphavantage.AlphaVantageDailyRequestKey
import archive.poc.alphavantage.AlphaVantageHttpPossession
import archive.poc.openfigi.OpenFigiForwardArchiveService
import archive.poc.openfigi.OpenFigiHttpPossession
import archive.poc.openfigi.OpenFigiMappingClient
import archive.poc.openfigi.OpenFigiMappingJob
import archive.poc.openfigi.OpenFigiMappingRequestBody
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProviderSymbolBindingEvidenceTest {
    private lateinit var root: Path
    private val ids = AtomicInteger(0)
    private val clockBase = Instant.parse("2026-09-16T00:00:00Z")
    private var clockIdx = 0

    private fun nextInstant(): Instant = clockBase.plusSeconds(clockIdx++.toLong())

    private val validPriceBody =
        """
        {
          "Meta Data": {
            "1. Information": "Daily Prices",
            "2. Symbol": "IBM",
            "3. Last Refreshed": "2026-09-15",
            "4. Output Size": "Compact",
            "5. Time Zone": "US/Eastern"
          },
          "Time Series (Daily)": {
            "2026-09-15": {
              "1. open": "1.0",
              "2. high": "2.0",
              "3. low": "0.5",
              "4. close": "1.5",
              "5. volume": "100"
            }
          }
        }
        """.trimIndent().toByteArray(StandardCharsets.UTF_8)

    private val validMappingBody =
        """[{"data":[{"figi":"BBG000BLNNH6","ticker":"IBM","exchCode":"US","compositeFIGI":"BBG000BLNNH6","shareClassFIGI":"BBG001S5S399","name":"INTL BUSINESS MACHINES CORP","securityType":"Common Stock","marketSector":"Equity"}]}]"""
            .toByteArray(StandardCharsets.UTF_8)

    private val multiFigiBody =
        """[{"data":[{"figi":"BBG000BLNNH6","ticker":"IBM"},{"figi":"BBG000BLNNH7","ticker":"IBM"}]}]"""
            .toByteArray(StandardCharsets.UTF_8)

    @BeforeTest
    fun setup() {
        root = Files.createTempDirectory("binding-evidence-poc")
        clockIdx = 0
        ids.set(0)
    }

    @AfterTest
    fun cleanup() {
        root.toFile().deleteRecursively()
    }

    private fun priceService(): AlphaVantageDailyForwardArchiveService =
        AlphaVantageDailyForwardArchiveService(
            archiveRoot = root,
            client =
                AlphaVantageDailyArchiveClient(
                    baseUrl = "http://127.0.0.1:1",
                    clock = { nextInstant() },
                ),
            clock = { nextInstant() },
            idGenerator = { "price-${ids.incrementAndGet()}" },
        )

    private fun mappingService(): OpenFigiForwardArchiveService =
        OpenFigiForwardArchiveService(
            archiveRoot = root,
            client = OpenFigiMappingClient(baseUrl = "http://127.0.0.1:1", clock = { nextInstant() }),
            clock = { nextInstant() },
            idGenerator = { "map-${ids.incrementAndGet()}" },
        )

    private fun pricePossession(
        symbol: String = "IBM",
        status: Int = 200,
        body: ByteArray? = validPriceBody.copyOf(),
    ): AlphaVantageHttpPossession {
        val key = AlphaVantageDailyArchiveClient.requestKey(symbol)
        val a = nextInstant()
        val b = nextInstant()
        return if (body == null) {
            AlphaVantageHttpPossession(
                requestKey = key,
                attemptedAt = a,
                attemptFinishedAt = b,
                fetchedAt = null,
                httpStatus = null,
                contentType = null,
                bodyBytes = null,
                transportFailureMessage = "ConnectException",
            )
        } else {
            AlphaVantageHttpPossession(
                requestKey = key,
                attemptedAt = a,
                attemptFinishedAt = b,
                fetchedAt = b,
                httpStatus = status,
                contentType = "application/json",
                bodyBytes = body,
                transportFailureMessage = null,
            )
        }
    }

    private fun mappingPossession(
        requestBytes: ByteArray,
        status: Int = 200,
        body: ByteArray? = validMappingBody.copyOf(),
    ): OpenFigiHttpPossession {
        val hash = Sha256Hex.of(requestBytes)
        val a = nextInstant()
        val b = nextInstant()
        return if (body == null) {
            OpenFigiHttpPossession(
                attemptedAt = a,
                attemptFinishedAt = b,
                fetchedAt = null,
                httpStatus = null,
                contentType = null,
                bodyBytes = null,
                transportFailureMessage = "ConnectException",
                requestPayloadHash = hash,
            )
        } else {
            OpenFigiHttpPossession(
                attemptedAt = a,
                attemptFinishedAt = b,
                fetchedAt = b,
                httpStatus = status,
                contentType = "application/json",
                bodyBytes = body,
                transportFailureMessage = null,
                requestPayloadHash = hash,
            )
        }
    }

    private fun archiveObservedPrice(symbol: String = "IBM"): ManifestRecord {
        val result =
            priceService().archivePossessedResponse(symbol, pricePossession(symbol = symbol))
        assertTrue(result.observedIngestSucceeded)
        return result.record
    }

    private fun archiveObservedMapping(
        jobs: List<OpenFigiMappingJob> =
            listOf(OpenFigiMappingJob(idType = "TICKER", idValue = "IBM", exchCode = "US")),
        responseBody: ByteArray = validMappingBody.copyOf(),
    ): ManifestRecord {
        val requestBytes = OpenFigiMappingRequestBody.encode(jobs)
        val result =
            mappingService().archivePossessedResponse(
                requestBytes,
                mappingPossession(requestBytes, body = responseBody),
            )
        assertTrue(result.observedIngestSucceeded, result.failureNotes)
        return result.record
    }

    @Test
    fun validPriceAndOneJobOneCandidateIsCandidate() {
        val price = archiveObservedPrice("IBM")
        val mapping = archiveObservedMapping()
        val evidence = ProviderSymbolBindingEvidenceDeriver.derive(price, mapping)
        assertEquals(ProviderSymbolBindingStatus.CANDIDATE, evidence.status)
        assertNull(evidence.reason)
        assertEquals("IBM", evidence.providerSymbol)
        assertEquals("alphavantage", evidence.priceProvider)
        assertEquals("TICKER", evidence.mappingIdType)
        assertEquals("IBM", evidence.mappingIdValue)
        assertEquals("US", evidence.mappingExchCode)
        assertEquals("figi", evidence.externalIdentifierNamespace)
        assertEquals("BBG000BLNNH6", evidence.externalIdentifier)
        assertEquals(
            maxOf(price.eligibilityBoundaryAt!!, mapping.eligibilityBoundaryAt!!),
            evidence.bindingEligibleAt,
        )
        assertFalse(evidence.toString().contains("securityId", ignoreCase = true))
        assertFalse(evidence.toString().contains("knownAt"))
        assertFalse(evidence.toString().contains("DailyPrice"))
        assertFalse(evidence.toString().contains("USD"))
    }

    @Test
    fun priceNotObservedIsIneligible() {
        val priceResult =
            priceService().archivePossessedResponse("IBM", pricePossession(body = null))
        assertFalse(priceResult.observedIngestSucceeded)
        val mapping = archiveObservedMapping()
        val evidence =
            ProviderSymbolBindingEvidenceDeriver.derive(priceResult.record, mapping)
        assertEquals(ProviderSymbolBindingStatus.INELIGIBLE, evidence.status)
        assertEquals(ProviderSymbolBindingReason.PRICE_NOT_OBSERVED, evidence.reason)
        assertNull(evidence.bindingEligibleAt)
    }

    @Test
    fun mappingNotObservedIsIneligible() {
        val price = archiveObservedPrice()
        val requestBytes =
            OpenFigiMappingRequestBody.encode(
                listOf(OpenFigiMappingJob(idType = "TICKER", idValue = "IBM")),
            )
        val mappingResult =
            mappingService().archivePossessedResponse(
                requestBytes,
                mappingPossession(requestBytes, body = null),
            )
        assertFalse(mappingResult.observedIngestSucceeded)
        val evidence =
            ProviderSymbolBindingEvidenceDeriver.derive(price, mappingResult.record)
        assertEquals(ProviderSymbolBindingStatus.INELIGIBLE, evidence.status)
        assertEquals(ProviderSymbolBindingReason.MAPPING_NOT_OBSERVED, evidence.reason)
    }

    @Test
    fun malformedPriceRequestKeyIsFailClosed() {
        val mapping = archiveObservedMapping()
        val t0 = Instant.parse("2026-09-16T01:00:00Z")
        val t1 = Instant.parse("2026-09-16T01:00:01Z")
        val badPrice =
            ManifestRecord(
                archiveId = "bad-price",
                domain = AlphaVantageDailyArchiveClient.DOMAIN,
                source = AlphaVantageDailyArchiveClient.SOURCE,
                requestKey = "GET|/query|function=TIME_SERIES_DAILY|symbol=IBM",
                attemptedAt = t0,
                attemptFinishedAt = t1,
                fetchedAt = t1,
                ingestedAt = t1,
                rawPayloadHash = "a".repeat(64),
                rawPayloadUri = "u",
                httpStatus = 200,
                transportStatus = TransportStatus.HTTP_RESPONSE,
                observationStatus = ObservationStatus.OBSERVED,
                eligibilityBoundaryAt = t1,
            )
        assertFailsWith<ArchiveValidationException> {
            ProviderSymbolBindingEvidenceDeriver.derive(badPrice, mapping)
        }
    }

    @Test
    fun callerSuppliedSymbolAloneCannotBind() {
        // Symbol must come from price requestKey — no free-form symbol API exists on deriver.
        val methods =
            ProviderSymbolBindingEvidenceDeriver::class.java.methods
                .filter { it.name == "derive" }
        assertTrue(methods.isNotEmpty())
        assertTrue(
            methods.none { m ->
                m.parameterTypes.any { it == String::class.java }
            },
        )
        assertEquals("IBM", AlphaVantageDailyRequestKey.parseOrThrow(
            AlphaVantageDailyArchiveClient.requestKey("IBM"),
        ).symbol)
    }

    @Test
    fun mappingWithoutRequestProvenanceIsIneligible() {
        val price = archiveObservedPrice()
        val t0 = Instant.parse("2026-09-16T02:00:00Z")
        val t1 = Instant.parse("2026-09-16T02:00:01Z")
        // LOCAL_ARCHIVE_FAILURE OpenFIGI row without request provenance fields.
        val mapping =
            ManifestRecord(
                archiveId = "no-req",
                domain = OpenFigiMappingClient.DOMAIN,
                source = OpenFigiMappingClient.SOURCE,
                requestKey = "POST|/v3/mapping|sha256:" + "a".repeat(64),
                attemptedAt = t0,
                attemptFinishedAt = t1,
                fetchedAt = t1,
                ingestedAt = t1,
                rawPayloadHash = "b".repeat(64),
                rawPayloadUri = null,
                httpStatus = 200,
                transportStatus = TransportStatus.LOCAL_FAILURE,
                observationStatus = ObservationStatus.LOCAL_ARCHIVE_FAILURE,
                eligibilityBoundaryAt = null,
            )
        val evidence = ProviderSymbolBindingEvidenceDeriver.derive(price, mapping)
        assertEquals(ProviderSymbolBindingStatus.INELIGIBLE, evidence.status)
        assertTrue(
            evidence.reason == ProviderSymbolBindingReason.MAPPING_NOT_OBSERVED ||
                evidence.reason == ProviderSymbolBindingReason.REQUEST_PROVENANCE_INVALID,
        )
    }

    @Test
    fun requestRawHashMismatchIsFailClosed() {
        val price = archiveObservedPrice()
        val mapping = archiveObservedMapping()
        val tampered = """[{"idType":"TICKER","idValue":"IBM"}]""".toByteArray(StandardCharsets.UTF_8)
        assertTrue(Sha256Hex.of(tampered) != mapping.requestPayloadHash)
        assertFailsWith<ArchiveValidationException> {
            ProviderSymbolBindingEvidenceDeriver.derive(
                price,
                mapping,
                mappingRequestBytes = tampered,
            )
        }
    }

    @Test
    fun requestIdValueMismatchIsIneligible() {
        val price = archiveObservedPrice("IBM")
        val mapping =
            archiveObservedMapping(
                jobs = listOf(OpenFigiMappingJob(idType = "TICKER", idValue = "AAPL", exchCode = "US")),
            )
        val evidence = ProviderSymbolBindingEvidenceDeriver.derive(price, mapping)
        assertEquals(ProviderSymbolBindingStatus.INELIGIBLE, evidence.status)
        assertEquals(ProviderSymbolBindingReason.REQUEST_SYMBOL_MISMATCH, evidence.reason)
    }

    @Test
    fun responseTickerMatchWithoutTickerRequestEvidenceFails() {
        val price = archiveObservedPrice("IBM")
        // Queried by FIGI, not TICKER — response may still contain ticker IBM.
        val mapping =
            archiveObservedMapping(
                jobs =
                    listOf(
                        OpenFigiMappingJob(idType = "ID_BB_GLOBAL", idValue = "BBG000BLNNH6"),
                    ),
            )
        val evidence = ProviderSymbolBindingEvidenceDeriver.derive(price, mapping)
        assertEquals(ProviderSymbolBindingStatus.INELIGIBLE, evidence.status)
        assertEquals(ProviderSymbolBindingReason.REQUEST_SYMBOL_MISMATCH, evidence.reason)
    }

    @Test
    fun multipleRequestJobsForbidAutoBinding() {
        val price = archiveObservedPrice("IBM")
        val jobs =
            listOf(
                OpenFigiMappingJob(idType = "TICKER", idValue = "IBM", exchCode = "US"),
                OpenFigiMappingJob(idType = "TICKER", idValue = "IBM", exchCode = "UN"),
            )
        val body =
            """[{"data":[{"figi":"BBG000BLNNH6","ticker":"IBM"}]},{"data":[{"figi":"BBG000BLNNH7","ticker":"IBM"}]}]"""
                .toByteArray(StandardCharsets.UTF_8)
        val mapping = archiveObservedMapping(jobs = jobs, responseBody = body)
        val evidence = ProviderSymbolBindingEvidenceDeriver.derive(price, mapping)
        assertEquals(ProviderSymbolBindingStatus.INELIGIBLE, evidence.status)
        assertEquals(ProviderSymbolBindingReason.MULTIPLE_REQUEST_JOBS, evidence.reason)
    }

    @Test
    fun multipleResponseCandidatesAreAmbiguousWithoutFirstFigiPick() {
        val price = archiveObservedPrice("IBM")
        val mapping = archiveObservedMapping(responseBody = multiFigiBody)
        assertNull(mapping.externalIdentifier)
        val evidence = ProviderSymbolBindingEvidenceDeriver.derive(price, mapping)
        assertEquals(ProviderSymbolBindingStatus.AMBIGUOUS, evidence.status)
        assertEquals(ProviderSymbolBindingReason.MULTIPLE_MAPPING_CANDIDATES, evidence.reason)
        assertNull(evidence.externalIdentifier)
        assertNull(evidence.externalIdentifierNamespace)
    }

    @Test
    fun bindingEligibleAtIsMaxOfPriceAndMapping_noBackfill() {
        clockIdx = 0
        // Force price earlier than mapping by sequencing archives with shared clock offsets.
        val priceEarly = archiveObservedPrice("IBM")
        // Advance clock significantly before mapping ingest.
        clockIdx += 100
        val mappingLate = archiveObservedMapping()
        assertTrue(mappingLate.eligibilityBoundaryAt!!.isAfter(priceEarly.eligibilityBoundaryAt))
        val evidence = ProviderSymbolBindingEvidenceDeriver.derive(priceEarly, mappingLate)
        assertEquals(ProviderSymbolBindingStatus.CANDIDATE, evidence.status)
        assertEquals(mappingLate.eligibilityBoundaryAt, evidence.bindingEligibleAt)
        // Must not equal price time (no backfill to earlier price eligibility).
        assertTrue(evidence.bindingEligibleAt!!.isAfter(priceEarly.eligibilityBoundaryAt))
    }

    @Test
    fun bindingEligibleAtUsesLaterPriceWhenMappingEarlier() {
        clockIdx = 0
        val mappingEarly = archiveObservedMapping()
        clockIdx += 100
        val priceLate = archiveObservedPrice("IBM")
        assertTrue(priceLate.eligibilityBoundaryAt!!.isAfter(mappingEarly.eligibilityBoundaryAt))
        val evidence = ProviderSymbolBindingEvidenceDeriver.derive(priceLate, mappingEarly)
        assertEquals(ProviderSymbolBindingStatus.CANDIDATE, evidence.status)
        assertEquals(priceLate.eligibilityBoundaryAt, evidence.bindingEligibleAt)
    }

    @Test
    fun conflictingCandidatesForSamePriceBecomeAmbiguous() {
        val price = archiveObservedPrice("IBM")
        val m1 = archiveObservedMapping()
        // Second distinct unique FIGI mapping for same symbol (different archive).
        val altBody =
            """[{"data":[{"figi":"BBG000B9XRY4","ticker":"IBM","exchCode":"US"}]}]"""
                .toByteArray(StandardCharsets.UTF_8)
        val m2 = archiveObservedMapping(responseBody = altBody)
        val e1 = ProviderSymbolBindingEvidenceDeriver.derive(price, m1)
        val e2 = ProviderSymbolBindingEvidenceDeriver.derive(price, m2)
        assertEquals(ProviderSymbolBindingStatus.CANDIDATE, e1.status)
        assertEquals(ProviderSymbolBindingStatus.CANDIDATE, e2.status)
        assertNotEquals(e1.externalIdentifier, e2.externalIdentifier)
        val resolved = ProviderSymbolBindingEvidenceDeriver.applyConflicts(listOf(e1, e2))
        assertTrue(resolved.all { it.status == ProviderSymbolBindingStatus.AMBIGUOUS })
        assertTrue(resolved.all { it.reason == ProviderSymbolBindingReason.CONFLICTING_CANDIDATES })
    }

    @Test
    fun agreeingRepeatedMappingsWithSameFigiStayCandidate() {
        val price = archiveObservedPrice("IBM")
        val m1 = archiveObservedMapping()
        val m2 = archiveObservedMapping() // same FIGI, different mappingArchiveId
        assertNotEquals(m1.archiveId, m2.archiveId)
        assertEquals(m1.externalIdentifier, m2.externalIdentifier)
        val e1 = ProviderSymbolBindingEvidenceDeriver.derive(price, m1)
        val e2 = ProviderSymbolBindingEvidenceDeriver.derive(price, m2)
        assertEquals(ProviderSymbolBindingStatus.CANDIDATE, e1.status)
        assertEquals(ProviderSymbolBindingStatus.CANDIDATE, e2.status)
        val resolved = ProviderSymbolBindingEvidenceDeriver.applyConflicts(listOf(e1, e2))
        assertTrue(resolved.all { it.status == ProviderSymbolBindingStatus.CANDIDATE })
        assertTrue(resolved.all { it.reason == null })
        assertTrue(resolved.all { it.externalIdentifier == "BBG000BLNNH6" })
    }

    @Test
    fun mappingArchiveIdDifferenceAloneIsNotConflictReason() {
        val price = archiveObservedPrice("IBM")
        val m1 = archiveObservedMapping()
        val m2 = archiveObservedMapping()
        val resolved =
            ProviderSymbolBindingEvidenceDeriver.applyConflicts(
                listOf(
                    ProviderSymbolBindingEvidenceDeriver.derive(price, m1),
                    ProviderSymbolBindingEvidenceDeriver.derive(price, m2),
                ),
            )
        assertEquals(2, resolved.map { it.mappingArchiveId }.distinct().size)
        assertTrue(resolved.none { it.reason == ProviderSymbolBindingReason.CONFLICTING_CANDIDATES })
        assertTrue(resolved.all { it.status == ProviderSymbolBindingStatus.CANDIDATE })
    }

    @Test
    fun manifestExternalIdMismatchVsResponseIsFailClosed() {
        val price = archiveObservedPrice("IBM")
        val mapping = archiveObservedMapping()
        val mismatched =
            mapping.copy(
                externalIdentifier = "BBG000B9XRY4",
                externalIdentifierNamespace = "figi",
            )
        assertNotEquals(mapping.externalIdentifier, mismatched.externalIdentifier)
        val ex =
            assertFailsWith<ArchiveValidationException> {
                ProviderSymbolBindingEvidenceDeriver.derive(price, mismatched)
            }
        assertTrue(ex.message!!.contains("MAPPING_MANIFEST_RESPONSE_MISMATCH"))
    }

    @Test
    fun manifestNamespaceMismatchVsResponseIsFailClosed() {
        val price = archiveObservedPrice("IBM")
        val mapping = archiveObservedMapping()
        val mismatched =
            mapping.copy(
                externalIdentifier = mapping.externalIdentifier,
                externalIdentifierNamespace = "other",
            )
        val ex =
            assertFailsWith<ArchiveValidationException> {
                ProviderSymbolBindingEvidenceDeriver.derive(price, mismatched)
            }
        assertTrue(ex.message!!.contains("MAPPING_MANIFEST_RESPONSE_MISMATCH"))
    }

    @Test
    fun uniqueResponseFigiWithMissingManifestExternalIdIsNotCandidate() {
        val price = archiveObservedPrice("IBM")
        val mapping = archiveObservedMapping()
        val withoutExt =
            mapping.copy(
                externalIdentifier = null,
                externalIdentifierNamespace = null,
            )
        val evidence = ProviderSymbolBindingEvidenceDeriver.derive(price, withoutExt)
        assertEquals(ProviderSymbolBindingStatus.INELIGIBLE, evidence.status)
        assertEquals(ProviderSymbolBindingReason.MISSING_EXTERNAL_IDENTIFIER, evidence.reason)
        assertNull(evidence.externalIdentifier)
    }

    @Test
    fun candidateInvariantRejectsBrokenManualConstruction() {
        val t0 = Instant.parse("2026-09-16T03:00:00Z")
        val t1 = Instant.parse("2026-09-16T03:00:01Z")
        assertFailsWith<IllegalArgumentException> {
            ProviderSymbolBindingEvidence(
                priceArchiveId = "p",
                priceProvider = "alphavantage",
                providerSymbol = "IBM",
                mappingArchiveId = "m",
                mappingRequestKey = "k",
                mappingRequestPayloadHash = "a".repeat(64),
                mappingRequestPayloadUri = "u",
                mappingIdType = "TICKER",
                mappingIdValue = "IBM",
                mappingExchCode = "US",
                externalIdentifierNamespace = "figi",
                externalIdentifier = "BBG000BLNNH6",
                priceEligibilityBoundaryAt = t0,
                mappingEligibilityBoundaryAt = t1,
                bindingEligibleAt = t0, // not max
                status = ProviderSymbolBindingStatus.CANDIDATE,
                reason = null,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            ProviderSymbolBindingEvidence(
                priceArchiveId = "p",
                priceProvider = "alphavantage",
                providerSymbol = "IBM",
                mappingArchiveId = "m",
                mappingRequestKey = "k",
                mappingRequestPayloadHash = "a".repeat(64),
                mappingRequestPayloadUri = "u",
                mappingIdType = "ID_BB_GLOBAL",
                mappingIdValue = "BBG000BLNNH6",
                mappingExchCode = null,
                externalIdentifierNamespace = "figi",
                externalIdentifier = "BBG000BLNNH6",
                priceEligibilityBoundaryAt = t0,
                mappingEligibilityBoundaryAt = t1,
                bindingEligibleAt = t1,
                status = ProviderSymbolBindingStatus.CANDIDATE,
                reason = null,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            ProviderSymbolBindingEvidence(
                priceArchiveId = "p",
                priceProvider = "alphavantage",
                providerSymbol = "IBM",
                mappingArchiveId = "m",
                mappingRequestKey = "k",
                mappingRequestPayloadHash = "a".repeat(64),
                mappingRequestPayloadUri = "u",
                mappingIdType = "TICKER",
                mappingIdValue = "IBM",
                mappingExchCode = "US",
                externalIdentifierNamespace = null,
                externalIdentifier = null,
                priceEligibilityBoundaryAt = t0,
                mappingEligibilityBoundaryAt = t1,
                bindingEligibleAt = t1,
                status = ProviderSymbolBindingStatus.CANDIDATE,
                reason = null,
            )
        }
    }

    @Test
    fun noSecurityIdCurrencyDailyPriceOrKnownAtInvented() {
        val evidence =
            ProviderSymbolBindingEvidenceDeriver.derive(
                archiveObservedPrice(),
                archiveObservedMapping(),
            )
        val text = evidence.toString()
        assertFalse(text.contains("SecurityId", ignoreCase = true))
        assertFalse(text.contains("knownAt"))
        assertFalse(text.contains("DailyPrice"))
        assertFalse(text.contains("currency", ignoreCase = true))
        assertFalse(text.contains("JoinCandidate"))
    }
}
