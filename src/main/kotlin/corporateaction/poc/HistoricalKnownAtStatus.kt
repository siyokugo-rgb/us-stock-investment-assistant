package corporateaction.poc

/**
 * historical knownAt（当時その split 予定/事実を根拠付きで知れたか）の解決状態。
 *
 * effectiveDate / exDate / announcement LocalDate 00:00 / fetchedAt を knownAt に転用してはならない。
 * timestamp 根拠が無ければ [UNRESOLVED]。
 */
enum class HistoricalKnownAtStatus {
    UNRESOLVED,
    RESOLVED_WITH_EVIDENCE,
}
