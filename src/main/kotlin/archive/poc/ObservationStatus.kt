package archive.poc

/**
 * Forward self-archive observation statuses (design contract).
 * Do not invent knownAt. Do not treat PROVIDER_FAILURE raw as decision input.
 */
enum class ObservationStatus {
    OBSERVED,
    REJECTED_VALIDATION,
    PROVIDER_FAILURE,
    MISSING,
}
