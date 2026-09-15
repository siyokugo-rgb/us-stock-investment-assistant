package archive.poc

import java.nio.file.Files
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

/**
 * Immutable raw object store: temp write → hash verify → atomic move into final path.
 * Never overwrites an existing final object.
 */
class ImmutableRawStore(
    private val archiveRoot: Path,
) {
    fun writeImmutable(
        relativeDir: String,
        archiveId: String,
        payload: ByteArray,
        expectedSha256Hex: String,
        /** Final object file name under [relativeDir]. Default response raw. */
        fileName: String = "$archiveId.raw",
    ): Path {
        require(archiveId.isNotBlank())
        require(fileName.isNotBlank()) { "fileName blank" }
        require(!fileName.contains('/') && !fileName.contains('\\')) {
            "fileName must not contain path separators"
        }
        require(expectedSha256Hex == Sha256Hex.of(payload)) {
            "Hash mismatch before write: expected=$expectedSha256Hex actual=${Sha256Hex.of(payload)}"
        }

        val dir = archiveRoot.resolve(relativeDir).normalize()
        if (!dir.startsWith(archiveRoot.normalize())) {
            throw ArchiveIoException("Refusing path escape: $dir")
        }
        try {
            Files.createDirectories(dir)
        } catch (e: Exception) {
            throw ArchiveIoException("Raw directory create failed for $relativeDir: ${e.message}", e)
        }

        val finalPath = dir.resolve(fileName)
        if (Files.exists(finalPath)) {
            throw ArchiveIoException("Raw path collision: $finalPath")
        }

        val tmp = dir.resolve("$fileName.tmp")
        try {
            Files.write(
                tmp,
                payload,
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE,
            )
            val onDisk = Files.readAllBytes(tmp)
            val onDiskHash = Sha256Hex.of(onDisk)
            if (onDiskHash != expectedSha256Hex) {
                throw ArchiveIoException("Hash verification failed after temp write: $onDiskHash")
            }
            try {
                Files.move(tmp, finalPath, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(tmp, finalPath)
            }
        } catch (e: ArchiveIoException) {
            runCatching { Files.deleteIfExists(tmp) }
            throw e
        } catch (e: Exception) {
            runCatching { Files.deleteIfExists(tmp) }
            throw ArchiveIoException("Raw write failed for $archiveId: ${e.message}", e)
        }

        val finalBytes = Files.readAllBytes(finalPath)
        val finalHash = Sha256Hex.of(finalBytes)
        if (finalHash != expectedSha256Hex) {
            throw ArchiveIoException("Hash verification failed after finalize: $finalHash")
        }
        return finalPath
    }

    /**
     * Lists final `*.raw` objects under [relativeDir] (non-recursive).
     * Temp (`*.raw.tmp`) files are ignored. Audit-only — never deletes.
     */
    fun listRawObjects(relativeDir: String): List<Path> {
        val dir = archiveRoot.resolve(relativeDir).normalize()
        if (!dir.startsWith(archiveRoot.normalize())) {
            throw ArchiveIoException("Refusing path escape: $dir")
        }
        if (!Files.exists(dir) || !Files.isDirectory(dir)) return emptyList()
        return Files.list(dir).use { stream ->
            stream
                .filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".raw") }
                .sorted()
                .toList()
        }
    }
}

class ArchiveIoException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
