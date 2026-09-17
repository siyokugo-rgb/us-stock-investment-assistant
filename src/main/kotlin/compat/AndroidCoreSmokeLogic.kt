package compat

import archive.poc.binding.Iso4217AlphabeticCodes
import archive.poc.binding.TradingCurrencyEvidence
import archive.poc.binding.TradingCurrencyEvidenceStatus
import archive.poc.binding.TradingCurrencyTemporalApplicability
import dividend.DividendEvent
import dividend.DividendType
import fundamentals.FactPeriod
import fundamentals.NormalizedFinancialConcept
import fundamentals.RawFinancialFact
import issuer.IssuerId
import market.DailyPrice
import security.SecurityId
import universe.poc.EvidenceStatus
import universe.poc.HistoricalKnownAtStatus
import universe.poc.MembershipQueryStatus
import universe.poc.ObservationCompleteness
import universe.poc.RawUniverseMembershipObservation
import universe.poc.UniverseMembershipPocQuery
import universe.poc.UniverseObservationType
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

/**
 * Network-free Android core compatibility checks over existing domain / PoC query types.
 *
 * Lives in the root JVM artifact so `:android-smoke` can stay Java/AGP-only.
 * Reason: applying `org.jetbrains.kotlin.android` in the same Gradle build as root
 * `org.jetbrains.kotlin.jvm` hits a KGP/AGP classloader failure (`BaseVariant` CNFE).
 * This is a smoke packaging choice — not a production Android architecture.
 *
 * Does not call Provider HTTP clients or live APIs.
 * Does not run TradingCurrencyEvidenceDeriver on-disk archive flow (separate integration).
 */
object AndroidCoreSmokeLogic {
    @JvmStatic
    fun run(): String =
        try {
            executeAllChecks()
            "ANDROID CORE SMOKE: PASS"
        } catch (t: Throwable) {
            buildString {
                append("ANDROID CORE SMOKE: FAIL\n")
                append(t::class.java.name)
                append(": ")
                append(t.message ?: "(no message)")
            }
        }

    private fun executeAllChecks() {
        val securityId = SecurityId("SEC-SMOKE-1")
        val issuerId = IssuerId("ISS-SMOKE-1")

        val tradingDate = LocalDate.of(2024, 6, 15)
        val priceKnownAt = Instant.parse("2024-06-16T00:00:00Z")
        val priceIngestedAt = Instant.parse("2024-06-16T01:00:00Z")
        val price =
            DailyPrice(
                securityId = securityId,
                tradingDate = tradingDate,
                open = BigDecimal("100.00"),
                high = BigDecimal("105.00"),
                low = BigDecimal("99.00"),
                close = BigDecimal("104.50"),
                volume = 1_000_000L,
                currency = "USD",
                knownAt = priceKnownAt,
                ingestedAt = priceIngestedAt,
                source = "android-smoke-fixture",
            )

        val dividend =
            DividendEvent(
                securityId = securityId,
                declarationDate = LocalDate.of(2024, 4, 15),
                exDate = LocalDate.of(2024, 5, 1),
                recordDate = LocalDate.of(2024, 5, 2),
                paymentDate = LocalDate.of(2024, 5, 15),
                amountPerShare = BigDecimal("0.25"),
                currency = "USD",
                dividendType = DividendType.REGULAR,
                knownAt = Instant.parse("2024-04-20T00:00:00Z"),
                ingestedAt = Instant.parse("2024-04-20T01:00:00Z"),
                source = "android-smoke-fixture",
            )

        val fact =
            RawFinancialFact(
                issuerId = issuerId,
                taxonomy = "us-gaap",
                concept = "Assets",
                unit = "USD",
                value = BigDecimal("1000000"),
                start = null,
                end = LocalDate.of(2023, 12, 31),
                fy = 2023,
                fp = "FY",
                form = "10-K",
                filed = LocalDate.of(2024, 2, 15),
                frame = null,
                accessionNumber = "0000000000-24-000001",
            )
        val period: FactPeriod = fact.period()
        val concept = NormalizedFinancialConcept.ASSETS

        require(tradingDate.isBefore(LocalDate.of(2024, 6, 16))) {
            "LocalDate comparison failed"
        }
        require(priceKnownAt.isAfter(Instant.parse("2024-06-15T00:00:00Z"))) {
            "Instant comparison failed"
        }
        require(price.close.compareTo(BigDecimal("100")) > 0) {
            "BigDecimal DailyPrice close check failed"
        }
        require(dividend.amountPerShare.signum() > 0) {
            "DividendEvent amount check failed"
        }
        require(period is FactPeriod.InstantPoint) {
            "FactPeriod should be InstantPoint for start=null"
        }
        require(concept == NormalizedFinancialConcept.ASSETS) {
            "NormalizedFinancialConcept mismatch"
        }
        require(securityId.value == "SEC-SMOKE-1")
        require(issuerId.value == "ISS-SMOKE-1")

        val asOf = LocalDate.of(2020, 6, 1)
        val knownAt = Instant.parse("2020-06-01T00:00:00Z")
        val decisionAt = Instant.parse("2020-06-02T00:00:00Z")
        val universeKey = "SMOKE_UNIVERSE"
        val observations =
            listOf(
                snapshotObservation(universeKey, "A", asOf, knownAt),
                snapshotObservation(universeKey, "B", asOf, knownAt),
            )
        val membership =
            UniverseMembershipPocQuery.membersAt(
                observations = observations,
                universeKey = universeKey,
                asOfDate = asOf,
                decisionAt = decisionAt,
            )
        require(membership.status == MembershipQueryStatus.MEMBERS) {
            "UniverseMembershipPocQuery status=${membership.status} reason=${membership.reason}"
        }
        require(membership.memberExternalIds == setOf("A", "B")) {
            "UniverseMembershipPocQuery members=${membership.memberExternalIds}"
        }

        executeTradingCurrencyEvidenceChecks()
    }

    /**
     * TradingCurrencyEvidence / Iso4217AlphabeticCodes / java.util.Currency runtime smoke.
     * Model + membership only — no Deriver on-disk archive flow.
     */
    private fun executeTradingCurrencyEvidenceChecks() {
        require(Iso4217AlphabeticCodes.isAlphabeticMember("USD")) {
            "Iso4217AlphabeticCodes USD membership expected true"
        }
        require(!Iso4217AlphabeticCodes.isAlphabeticMember("ABC")) {
            "Iso4217AlphabeticCodes ABC membership expected false"
        }
        require(!TradingCurrencyEvidence.CANONICAL_ISO_ALPHA.matches("usd")) {
            "lowercase usd must not match canonical ^[A-Z]{3}$ (no auto-repair)"
        }

        val elig = Instant.parse("2026-09-17T03:00:00Z")
        val candidate =
            TradingCurrencyEvidence(
                priceArchiveId = "android-smoke-price",
                allTickersArchiveId = "android-smoke-all-tickers",
                provider = TradingCurrencyEvidence.PROVIDER_MASSIVE,
                providerTicker = "AAPL",
                rawCurrencySymbol = "USD",
                canonicalCurrencyCode = "USD",
                priceEligibilityBoundaryAt = elig,
                allTickersEligibilityBoundaryAt = elig,
                evidenceEligibleAt = elig,
                allTickersRequestDate = null,
                temporalApplicability = TradingCurrencyTemporalApplicability.UNRESOLVED,
                status = TradingCurrencyEvidenceStatus.CANDIDATE,
                reason = null,
            )
        require(candidate.status == TradingCurrencyEvidenceStatus.CANDIDATE) {
            "TradingCurrencyEvidence status expected CANDIDATE"
        }
        require(candidate.canonicalCurrencyCode == "USD") {
            "TradingCurrencyEvidence canonicalCurrencyCode expected USD"
        }
        require(
            candidate.temporalApplicability == TradingCurrencyTemporalApplicability.UNRESOLVED,
        ) {
            "TradingCurrencyEvidence temporalApplicability expected UNRESOLVED"
        }
    }

    private fun snapshotObservation(
        universeKey: String,
        member: String,
        asOf: LocalDate,
        knownAt: Instant,
    ): RawUniverseMembershipObservation =
        RawUniverseMembershipObservation(
            universeKey = universeKey,
            provider = "android-smoke-fixture",
            officialIndexId = "SMOKE",
            observationType = UniverseObservationType.SNAPSHOT,
            memberExternalId = member,
            memberIdentifierType = "FIXTURE_ID",
            tickerRaw = member,
            membershipEffectiveDate = asOf,
            membershipEffectiveAt = null,
            announcedAt = null,
            knownAt = knownAt,
            knownAtStatus = HistoricalKnownAtStatus.RESOLVED_WITH_EVIDENCE,
            sourceDocumentId = "smoke-snapshot",
            sourceUrl = null,
            fetchedAt = Instant.parse("2026-09-11T12:00:00Z"),
            sourceContentSha256 = null,
            evidenceStatus = EvidenceStatus.SYNTHETIC_FIXTURE_ONLY,
            completeness = ObservationCompleteness.SINGLE_SNAPSHOT,
            coverageStartDate = null,
            coverageThroughDate = null,
        )
}
