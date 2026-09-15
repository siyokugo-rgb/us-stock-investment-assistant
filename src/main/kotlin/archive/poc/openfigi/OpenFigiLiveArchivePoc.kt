package archive.poc.openfigi

/**
 * Optional live smoke for OpenFIGI mapping archive.
 *
 * Env:
 * - OPENFIGI_API_KEY (optional; never logged)
 * - OPENFIGI_ARCHIVE_ROOT (optional; default ./archive-runtime)
 *
 * Exit:
 * - 0 on OBSERVED ingest success
 * - 2 on provider/transport/local failure (PoC not a product outage)
 * - Does not mock fallback.
 */
fun main() {
    val env = System.getenv()
    val root =
        java.nio.file.Path.of(
            env["OPENFIGI_ARCHIVE_ROOT"]?.trim()?.takeIf { it.isNotEmpty() }
                ?: "archive-runtime",
        )
    val client = OpenFigiMappingClient.fromEnvironment(env)
    val service =
        OpenFigiForwardArchiveService(
            archiveRoot = root,
            client = client,
        )
    val jobs =
        listOf(
            OpenFigiMappingJob(
                idType = "ID_BB_GLOBAL",
                idValue = "BBG000BLNNH6",
            ),
        )
    println("OpenFIGI forward archive live smoke")
    println("domain=${OpenFigiMappingClient.DOMAIN} source=${OpenFigiMappingClient.SOURCE}")
    println("endpoint=POST ${OpenFigiMappingClient.DEFAULT_BASE_URL}${OpenFigiMappingClient.MAPPING_PATH}")
    println("apiKeyConfigured=${!env[OpenFigiMappingClient.ENV_API_KEY].isNullOrBlank()}")
    println("archiveRoot=$root")

    val result = service.archiveMapping(jobs)
    val r = result.record
    println("archiveId=${r.archiveId}")
    println("observationStatus=${r.observationStatus}")
    println("httpStatus=${r.httpStatus}")
    println("transportStatus=${r.transportStatus}")
    println("fetchedAt=${r.fetchedAt}")
    println("ingestedAt=${r.ingestedAt}")
    println("rawPayloadHash=${r.rawPayloadHash}")
    println("rawPayloadUri=${r.rawPayloadUri}")
    println("eligibilityBoundaryAt=${r.eligibilityBoundaryAt}")
    println("observedIngestSucceeded=${result.observedIngestSucceeded}")
    println("coverageStartAt=${result.coverage.coverageStartAt}")
    println("coverageThroughAt=${result.coverage.coverageThroughAt}")
    if (result.failureNotes != null) {
        println("failureNotes=${result.failureNotes}")
    }
    if (!result.observedIngestSucceeded) {
        kotlin.system.exitProcess(2)
    }
}
