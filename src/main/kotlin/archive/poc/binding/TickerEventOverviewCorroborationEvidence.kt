package archive.poc.binding

import java.time.Instant

/**
 * Corroboration of an Overview-derived [SecurityIdentityContinuityStatus.TICKER_CHANGE_CANDIDATE]
 * against independent Massive Ticker Events raw evidence (synthetic-first PoC).
 *
 * Does **not** invent ticker change candidates from Events alone.
 * Does **not** emit SecurityId / SecurityIdentifier / knownAt / validFrom / validTo / DailyPrice.
 *
 * [corroborationEligibleAt] is combined **evidence availability** only
 * (`max(continuity evidenceEligibleAt, tickerEvents eligibility)`) —
 * ≠ knownAt / identity validity / ticker validity.
 *
 * Event date window match ≠ effective-date proof ≠ OLD→NEW invention.
 *
 * PoC scope: Ticker Events **ticker lookup only** —
 * [tickerEventsLookupId] must equal [secondProviderTicker] for CORROBORATED.
 * That equality is **request provenance restriction**, not SecurityId /
 * Security identity proof. CUSIP / Composite FIGI lookup corroboration = deferred.
 */
enum class TickerEventOverviewCorroborationStatus {
    CORROBORATED_TICKER_CHANGE_CANDIDATE,
    UNRESOLVED,
}

enum class TickerEventOverviewCorroborationReason {
    OVERVIEW_SOURCE_REQUIRED,
    CONTINUITY_NOT_TICKER_CHANGE_CANDIDATE,
    EVENT_INPUT_NOT_OBSERVED,
    EVENT_SOURCE_UNSUPPORTED,
    EVENT_RAW_VALIDATION_FAILED,
    EVENTS_EMPTY,
    /** Events requestKey lookupId ≠ later Overview provider ticker (PoC ticker-lookup scope). */
    EVENT_LOOKUP_NOT_LATER_TICKER,
    NO_EVENT_WINDOW_MATCH,
    EVENT_WINDOW_MATCH_AMBIGUOUS,
}

data class TickerEventOverviewCorroborationEvidence(
    val firstOverviewArchiveId: String,
    val secondOverviewArchiveId: String,
    val tickerEventsArchiveId: String,
    val firstProviderTicker: String?,
    val secondProviderTicker: String?,
    val firstProviderAsOfDate: String?,
    val secondProviderAsOfDate: String?,
    val firstShareClassFigi: String?,
    val secondShareClassFigi: String?,
    val continuityStatus: SecurityIdentityContinuityStatus,
    /**
     * Opaque Events requestKey lookup id — request provenance only; ≠ SecurityId.
     * On CORROBORATED must equal [secondProviderTicker] (PoC ticker-lookup scope).
     */
    val tickerEventsLookupId: String?,
    val matchedEventType: String?,
    val matchedEventDate: String?,
    val matchedEventTicker: String?,
    val continuityEvidenceEligibleAt: Instant?,
    val tickerEventsEligibilityBoundaryAt: Instant?,
    /**
     * max(continuityEvidenceEligibleAt, tickerEventsEligibilityBoundaryAt) when both present
     * on CORROBORATED. Availability only — ≠ knownAt / validFrom / validTo.
     */
    val corroborationEligibleAt: Instant?,
    val status: TickerEventOverviewCorroborationStatus,
    val reason: TickerEventOverviewCorroborationReason?,
) {
    init {
        require(firstOverviewArchiveId.isNotBlank())
        require(secondOverviewArchiveId.isNotBlank())
        require(tickerEventsArchiveId.isNotBlank())
        when (status) {
            TickerEventOverviewCorroborationStatus.CORROBORATED_TICKER_CHANGE_CANDIDATE -> {
                require(reason == null) { "CORROBORATED must not carry reason" }
                require(
                    continuityStatus ==
                        SecurityIdentityContinuityStatus.TICKER_CHANGE_CANDIDATE,
                )
                require(!firstProviderTicker.isNullOrBlank() && !secondProviderTicker.isNullOrBlank())
                require(firstProviderTicker != secondProviderTicker)
                require(!firstShareClassFigi.isNullOrBlank() && !secondShareClassFigi.isNullOrBlank())
                require(firstShareClassFigi == secondShareClassFigi)
                require(firstProviderAsOfDate != null && secondProviderAsOfDate != null)
                require(firstProviderAsOfDate!! < secondProviderAsOfDate!!)
                require(matchedEventType == "ticker_change")
                require(!matchedEventDate.isNullOrBlank())
                require(!matchedEventTicker.isNullOrBlank())
                require(matchedEventTicker == secondProviderTicker)
                require(!tickerEventsLookupId.isNullOrBlank()) {
                    "CORROBORATED requires nonblank tickerEventsLookupId"
                }
                require(tickerEventsLookupId == secondProviderTicker) {
                    "CORROBORATED requires tickerEventsLookupId == secondProviderTicker " +
                        "(PoC ticker-lookup provenance; ≠ SecurityId equality)"
                }
                require(
                    firstProviderAsOfDate < matchedEventDate!! &&
                        matchedEventDate <= secondProviderAsOfDate,
                ) {
                    "matchedEventDate must satisfy (firstDate, secondDate]"
                }
                require(continuityEvidenceEligibleAt != null)
                require(tickerEventsEligibilityBoundaryAt != null)
                require(corroborationEligibleAt != null)
                val expected =
                    if (continuityEvidenceEligibleAt!!.isAfter(tickerEventsEligibilityBoundaryAt!!)) {
                        continuityEvidenceEligibleAt
                    } else {
                        tickerEventsEligibilityBoundaryAt
                    }
                require(corroborationEligibleAt == expected) {
                    "corroborationEligibleAt must equal max(continuity, events) eligibility"
                }
            }
            TickerEventOverviewCorroborationStatus.UNRESOLVED -> {
                require(reason != null) { "UNRESOLVED requires reason" }
            }
        }
    }
}
