package archive.poc

/**
 * Forward self-archive observation statuses (design contract).
 * Do not invent knownAt. Do not treat PROVIDER_FAILURE / LOCAL_ARCHIVE_FAILURE as decision input.
 */
enum class ObservationStatus {
    OBSERVED,
    REJECTED_VALIDATION,
    PROVIDER_FAILURE,
    LOCAL_ARCHIVE_FAILURE,
    MISSING,
}
