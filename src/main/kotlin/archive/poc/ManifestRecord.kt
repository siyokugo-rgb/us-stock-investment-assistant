package archive.poc

import java.time.Instant

data class ManifestRecord(
    val archiveId: String,
    val domain: String,
    val source: String,
    val requestKey: String,
    val externalIdentifier: String? = null,
    val externalIdentifierNamespace: String? = null,
    val asOfParam: String? = null,
    val observedFields: List<String> = emptyList(),
    val validityPeriod: String? = null,
    val attemptedAt: Instant,
    val attemptFinishedAt: Instant,
    val fetchedAt: Instant? = null,
    val ingestedAt: Instant,
    val rawPayloadHash: String? = null,
    val storageObjectHash: String? = null,
    val rawPayloadUri: String? = null,
    /** Exact HTTP request body SHA-256 (lowercase hex). Optional; OpenFIGI uses for binding provenance. */
    val requestPayloadHash: String? = null,
    /** Immutable URI of secret-free request body object. Paired with [requestPayloadHash]. */
    val requestPayloadUri: String? = null,
    val contentType: String? = null,
    val httpStatus: Int? = null,
    val transportStatus: TransportStatus,
    val revisionCandidateOf: String? = null,
    val relatedPriorObservationIds: List<String> = emptyList(),
    val duplicateOf: String? = null,
    val observationStatus: ObservationStatus,
    val eligibilityBoundaryAt: Instant? = null,
    val notes: String? = null,
) {
    init {
        require(archiveId.isNotBlank()) { "archiveId blank" }
        require(domain.isNotBlank()) { "domain blank" }
        require(source.isNotBlank()) { "source blank" }
        require(requestKey.isNotBlank()) { "requestKey blank" }
        require(!attemptFinishedAt.isBefore(attemptedAt)) {
            "attemptFinishedAt before attemptedAt"
        }
        fetchedAt?.let {
            require(!it.isBefore(attemptedAt)) { "fetchedAt before attemptedAt" }
            require(!attemptFinishedAt.isBefore(it)) { "attemptFinishedAt before fetchedAt" }
            require(!ingestedAt.isBefore(it)) { "ingestedAt before fetchedAt" }
        }
        require(!ingestedAt.isBefore(attemptFinishedAt)) {
            "ingestedAt before attemptFinishedAt"
        }
        require((externalIdentifier == null) == (externalIdentifierNamespace == null)) {
            "externalIdentifier and externalIdentifierNamespace must both be null or both non-null"
        }
        require((requestPayloadHash == null) == (requestPayloadUri == null)) {
            "requestPayloadHash and requestPayloadUri must both be null or both non-null"
        }
        if (requestPayloadHash != null) {
            require(requestPayloadHash.isNotBlank()) { "requestPayloadHash blank" }
            require(!requestPayloadUri.isNullOrBlank()) { "requestPayloadUri blank" }
        }
        validateOpenFigiRequestKeyHashInvariant()
        validateStatusInvariants()
    }

    private fun isOpenFigi(): Boolean =
        domain == "SECURITY_MASTER" && source == "openfigi.v3.mapping"

    /**
     * OpenFIGI-only: when requestPayloadHash is present, it must be `^[0-9a-f]{64}$` and
     * requestKey must be `POST|/v3/mapping|sha256:{requestPayloadHash}` (exact hash match).
     * Enforced on construction and [fromJsonLine] reload. Other domains unaffected.
     */
    private fun validateOpenFigiRequestKeyHashInvariant() {
        if (!isOpenFigi()) return
        if (requestPayloadHash == null) return
        require(OPENFIGI_REQUEST_PAYLOAD_HASH_REGEX.matches(requestPayloadHash)) {
            "OpenFIGI requestPayloadHash must be 64-char lowercase hex SHA-256"
        }
        val prefix = "POST|/v3/mapping|sha256:"
        require(requestKey.startsWith(prefix)) {
            "OpenFIGI requestKey must start with $prefix"
        }
        val keyHash = requestKey.removePrefix(prefix)
        require(OPENFIGI_REQUEST_PAYLOAD_HASH_REGEX.matches(keyHash)) {
            "OpenFIGI requestKey body hash must be 64-char lowercase hex SHA-256"
        }
        require(keyHash == requestPayloadHash) {
            "OpenFIGI requestKey body hash mismatch vs requestPayloadHash"
        }
    }

    /** OpenFIGI-only: request body provenance required for durable mapping statuses. */
    private fun requireOpenFigiRequestProvenance(statusLabel: String) {
        if (!isOpenFigi()) return
        require(!requestPayloadHash.isNullOrBlank()) {
            "$statusLabel OpenFIGI requires requestPayloadHash"
        }
        require(!requestPayloadUri.isNullOrBlank()) {
            "$statusLabel OpenFIGI requires requestPayloadUri"
        }
    }

    private fun validateStatusInvariants() {
        when (observationStatus) {
            ObservationStatus.OBSERVED -> {
                require(fetchedAt != null) { "OBSERVED requires fetchedAt" }
                require(!rawPayloadHash.isNullOrBlank()) { "OBSERVED requires rawPayloadHash" }
                require(!rawPayloadUri.isNullOrBlank()) { "OBSERVED requires rawPayloadUri" }
                require(eligibilityBoundaryAt != null) { "OBSERVED requires eligibilityBoundaryAt" }
                require(httpStatus != null && httpStatus in 200..299) {
                    "OBSERVED requires HTTP success status"
                }
                require(transportStatus == TransportStatus.HTTP_RESPONSE) {
                    "OBSERVED requires HTTP_RESPONSE transportStatus"
                }
                requireOpenFigiRequestProvenance("OBSERVED")
            }
            ObservationStatus.REJECTED_VALIDATION -> {
                require(fetchedAt != null) { "REJECTED_VALIDATION requires fetchedAt" }
                require(!rawPayloadHash.isNullOrBlank()) {
                    "REJECTED_VALIDATION requires rawPayloadHash"
                }
                require(!rawPayloadUri.isNullOrBlank()) {
                    "REJECTED_VALIDATION requires rawPayloadUri"
                }
                require(eligibilityBoundaryAt == null) {
                    "REJECTED_VALIDATION requires eligibilityBoundaryAt=null"
                }
                requireOpenFigiRequestProvenance("REJECTED_VALIDATION")
            }
            ObservationStatus.PROVIDER_FAILURE -> {
                require(eligibilityBoundaryAt == null) {
                    "PROVIDER_FAILURE requires eligibilityBoundaryAt=null"
                }
                when (transportStatus) {
                    TransportStatus.TRANSPORT_FAILURE -> {
                        require(fetchedAt == null) {
                            "transport PROVIDER_FAILURE requires fetchedAt=null"
                        }
                        require(rawPayloadHash == null) {
                            "transport PROVIDER_FAILURE requires rawPayloadHash=null"
                        }
                        require(rawPayloadUri == null) {
                            "transport PROVIDER_FAILURE requires rawPayloadUri=null"
                        }
                        // OpenFIGI writes secret-free request raw before the response path.
                        requireOpenFigiRequestProvenance("PROVIDER_FAILURE/TRANSPORT_FAILURE")
                    }
                    TransportStatus.HTTP_RESPONSE -> {
                        require(fetchedAt != null) {
                            "HTTP PROVIDER_FAILURE requires fetchedAt"
                        }
                        require(!rawPayloadHash.isNullOrBlank()) {
                            "HTTP PROVIDER_FAILURE requires rawPayloadHash"
                        }
                        require(!rawPayloadUri.isNullOrBlank()) {
                            "HTTP PROVIDER_FAILURE requires rawPayloadUri"
                        }
                        require(httpStatus != null && httpStatus !in 200..299) {
                            "HTTP PROVIDER_FAILURE requires non-success httpStatus"
                        }
                        requireOpenFigiRequestProvenance("PROVIDER_FAILURE/HTTP_RESPONSE")
                    }
                    TransportStatus.LOCAL_FAILURE ->
                        throw IllegalArgumentException(
                            "PROVIDER_FAILURE must not use LOCAL_FAILURE transportStatus; use LOCAL_ARCHIVE_FAILURE",
                        )
                }
            }
            ObservationStatus.LOCAL_ARCHIVE_FAILURE -> {
                require(eligibilityBoundaryAt == null) {
                    "LOCAL_ARCHIVE_FAILURE requires eligibilityBoundaryAt=null"
                }
                // Body may or may not have been received; if present, hash must accompany fetchedAt.
                if (fetchedAt != null) {
                    require(!rawPayloadHash.isNullOrBlank()) {
                        "LOCAL_ARCHIVE_FAILURE with fetchedAt requires rawPayloadHash"
                    }
                } else {
                    require(rawPayloadHash == null) {
                        "LOCAL_ARCHIVE_FAILURE without fetchedAt requires rawPayloadHash=null"
                    }
                    require(rawPayloadUri == null) {
                        "LOCAL_ARCHIVE_FAILURE without fetchedAt requires rawPayloadUri=null"
                    }
                }
            }
            ObservationStatus.MISSING -> {
                require(fetchedAt == null) { "MISSING requires fetchedAt=null" }
                require(rawPayloadHash == null) { "MISSING requires rawPayloadHash=null" }
                require(rawPayloadUri == null) { "MISSING requires rawPayloadUri=null" }
                require(eligibilityBoundaryAt == null) {
                    "MISSING requires eligibilityBoundaryAt=null"
                }
            }
        }
    }

    fun toJsonLine(): String {
        val parts = mutableListOf<String>()
        fun put(
            name: String,
            value: String?,
        ) {
            if (value != null) parts += "${quote(name)}:${quote(value)}"
        }

        fun putArr(
            name: String,
            values: List<String>,
        ) {
            val body = values.joinToString(",") { quote(it) }
            parts += "${quote(name)}:[$body]"
        }

        fun putNum(
            name: String,
            value: Int?,
        ) {
            if (value != null) parts += "${quote(name)}:$value"
        }

        put("archiveId", archiveId)
        put("domain", domain)
        put("source", source)
        put("requestKey", requestKey)
        put("externalIdentifier", externalIdentifier)
        put("externalIdentifierNamespace", externalIdentifierNamespace)
        put("asOfParam", asOfParam)
        putArr("observedFields", observedFields)
        put("validityPeriod", validityPeriod)
        put("attemptedAt", attemptedAt.toString())
        put("attemptFinishedAt", attemptFinishedAt.toString())
        put("fetchedAt", fetchedAt?.toString())
        put("ingestedAt", ingestedAt.toString())
        put("rawPayloadHash", rawPayloadHash)
        put("storageObjectHash", storageObjectHash)
        put("rawPayloadUri", rawPayloadUri)
        put("requestPayloadHash", requestPayloadHash)
        put("requestPayloadUri", requestPayloadUri)
        put("contentType", contentType)
        putNum("httpStatus", httpStatus)
        put("transportStatus", transportStatus.name)
        put("revisionCandidateOf", revisionCandidateOf)
        putArr("relatedPriorObservationIds", relatedPriorObservationIds)
        put("duplicateOf", duplicateOf)
        put("observationStatus", observationStatus.name)
        put("eligibilityBoundaryAt", eligibilityBoundaryAt?.toString())
        put("notes", notes)
        return parts.joinToString(",", "{", "}")
    }

    companion object {
        private val OPENFIGI_REQUEST_PAYLOAD_HASH_REGEX = Regex("^[0-9a-f]{64}$")

        fun fromJsonLine(line: String): ManifestRecord {
            val obj = ArchiveJson.parse(line).asObject("manifest")
            fun str(key: String): String? =
                when (val v = obj.map[key]) {
                    null -> null
                    is ArchiveJson.Null -> null
                    is ArchiveJson.Str -> v.value
                    else -> throw ArchiveValidationException("manifest.$key must be string")
                }

            fun strReq(key: String): String =
                str(key) ?: throw ArchiveValidationException("manifest missing $key")

            fun arr(key: String): List<String> =
                when (val v = obj.map[key]) {
                    null, is ArchiveJson.Null -> emptyList()
                    is ArchiveJson.Arr ->
                        v.items.map {
                            (it as? ArchiveJson.Str)?.value
                                ?: throw ArchiveValidationException("manifest.$key items must be strings")
                        }
                    else -> throw ArchiveValidationException("manifest.$key must be array")
                }

            fun intOrNull(key: String): Int? =
                when (val v = obj.map[key]) {
                    null, is ArchiveJson.Null -> null
                    is ArchiveJson.Num -> v.value.toInt()
                    else -> throw ArchiveValidationException("manifest.$key must be number")
                }

            return try {
                ManifestRecord(
                    archiveId = strReq("archiveId"),
                    domain = strReq("domain"),
                    source = strReq("source"),
                    requestKey = strReq("requestKey"),
                    externalIdentifier = str("externalIdentifier"),
                    externalIdentifierNamespace = str("externalIdentifierNamespace"),
                    asOfParam = str("asOfParam"),
                    observedFields = arr("observedFields"),
                    validityPeriod = str("validityPeriod"),
                    attemptedAt = Instant.parse(strReq("attemptedAt")),
                    attemptFinishedAt = Instant.parse(strReq("attemptFinishedAt")),
                    fetchedAt = str("fetchedAt")?.let(Instant::parse),
                    ingestedAt = Instant.parse(strReq("ingestedAt")),
                    rawPayloadHash = str("rawPayloadHash"),
                    storageObjectHash = str("storageObjectHash"),
                    rawPayloadUri = str("rawPayloadUri"),
                    requestPayloadHash = str("requestPayloadHash"),
                    requestPayloadUri = str("requestPayloadUri"),
                    contentType = str("contentType"),
                    httpStatus = intOrNull("httpStatus"),
                    transportStatus = TransportStatus.valueOf(strReq("transportStatus")),
                    revisionCandidateOf = str("revisionCandidateOf"),
                    relatedPriorObservationIds = arr("relatedPriorObservationIds"),
                    duplicateOf = str("duplicateOf"),
                    observationStatus = ObservationStatus.valueOf(strReq("observationStatus")),
                    eligibilityBoundaryAt = str("eligibilityBoundaryAt")?.let(Instant::parse),
                    notes = str("notes"),
                )
            } catch (e: IllegalArgumentException) {
                throw ArchiveValidationException(
                    "semantic-invalid manifest: ${e.message}",
                    e,
                )
            }
        }

        private fun quote(value: String): String =
            buildString {
                append('"')
                value.forEach { c ->
                    when (c) {
                        '\\' -> append("\\\\")
                        '"' -> append("\\\"")
                        '\n' -> append("\\n")
                        '\r' -> append("\\r")
                        '\t' -> append("\\t")
                        else -> append(c)
                    }
                }
                append('"')
            }
    }
}
