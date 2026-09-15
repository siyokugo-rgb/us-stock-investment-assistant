package archive.poc

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/**
 * Append-only JSONL manifest. Existing lines are never rewritten.
 * Malformed lines Fail-Closed on read.
 */
class ManifestStore(
    private val manifestPath: Path,
) {
    init {
        Files.createDirectories(manifestPath.parent)
        if (!Files.exists(manifestPath)) {
            Files.createFile(manifestPath)
        }
    }

    fun append(record: ManifestRecord) {
        val line = record.toJsonLine() + "\n"
        ManifestRecord.fromJsonLine(line.trimEnd())
        try {
            Files.writeString(
                manifestPath,
                line,
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND,
            )
        } catch (e: Exception) {
            throw ArchiveIoException("Manifest append failed: ${e.message}", e)
        }
    }

    fun readAll(): List<ManifestRecord> {
        if (!Files.exists(manifestPath)) return emptyList()
        val lines = Files.readAllLines(manifestPath, StandardCharsets.UTF_8)
        return lines.mapIndexed { idx, raw ->
            val line = raw.trim()
            if (line.isEmpty()) {
                throw ArchiveIoException("Malformed manifest: empty line at ${idx + 1}")
            }
            try {
                ManifestRecord.fromJsonLine(line)
            } catch (e: Exception) {
                throw ArchiveIoException("Malformed manifest line ${idx + 1}: ${e.message}", e)
            }
        }
    }

    fun findByRequestKey(
        domain: String,
        source: String,
        requestKey: String,
    ): List<ManifestRecord> =
        readAll().filter {
            it.domain == domain && it.source == source && it.requestKey == requestKey
        }
}
