package corporateaction.poc

/**
 * historical knownAt（当時その split 予定/事実を根拠付きで知れたか）の解決状態。
 *
 * [RawSplitObservation.knownAt] と対になる。
 * - UNRESOLVED → knownAt == null
 * - RESOLVED_WITH_EVIDENCE → knownAt != null
 *
 * [RawSplitObservation.announcedAt] とは別責務。
 * announcedAt / effectiveDate / exDate / announcement LocalDate 00:00 / fetchedAt を
 * knownAt に転用・自動コピーしてはならない。
 * timestamp 根拠が無ければ [UNRESOLVED]。
 */
enum class HistoricalKnownAtStatus {
    UNRESOLVED,
    RESOLVED_WITH_EVIDENCE,
}
