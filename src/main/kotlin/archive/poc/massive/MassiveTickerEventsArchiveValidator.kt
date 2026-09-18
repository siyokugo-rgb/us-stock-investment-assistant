package archive.poc.massive

import archive.poc.ArchiveJson
import archive.poc.ArchiveValidationException
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.time.LocalDate
import java.time.format.DateTimeParseException

/** Raw ticker-change evidence DTO — not a domain event; no old/new / SecurityId. */
data class MassiveTickerChangeRawEvidence(
    val type: String,
    val date: String,
    val ticker: String,
)

data class MassiveTickerEventsValidationOutcome(
    val okForObserved: Boolean,
    val observedFields: List<String>,
    val notes: String?,
    val eventCount: Int = 0,
    /** Present when [okForObserved]; raw evidence only — never continuity / SecurityId. */
    val validatedEvents: List<MassiveTickerChangeRawEvidence> = emptyList(),
)

/**
 * Fail-Closed validation for Massive Stocks Ticker Events possession.
 *
 * Official root Response Attributes are optional; this PoC still requires
 * status=="OK" + results object + results.events array for OBSERVED eligibility
 * (policy ≠ claiming official requiredness).
 *
 * Does not invent old/new tickers, sort/dedupe events, or generate
 * SecurityId / SecurityIdentifier / knownAt / validFrom / validTo / continuity.
 */
object MassiveTickerEventsArchiveValidator {
    fun validate(
        httpStatus: Int,
        bodyBytes: ByteArray,
    ): MassiveTickerEventsValidationOutcome {
        if (bodyBytes.isEmpty()) return reject("empty body")
        if (httpStatus !in 200..299) {
            return reject("httpStatus=$httpStatus is not a success envelope for OBSERVED")
        }
        val text =
            try {
                decodeUtf8Strict(bodyBytes)
            } catch (_: Exception) {
                return reject("body is not valid UTF-8")
            }

        val root =
            try {
                ArchiveJson.parse(text).asObject("massive ticker events")
            } catch (e: ArchiveValidationException) {
                return reject("malformed Massive JSON: ${e.message}")
            } catch (e: Exception) {
                return reject("malformed Massive JSON: ${e.message}")
            }

        // Pagination indicator: complete single-response archive not guaranteed.
        when (val next = root.map["next_url"]) {
            null, is ArchiveJson.Null -> Unit
            is ArchiveJson.Str ->
                return reject(
                    "next_url present; incomplete single-response archive " +
                        "(pagination required; next_url not followed)",
                )
            else ->
                return reject(
                    "next_url present with unexpected type; incomplete single-response archive",
                )
        }

        val status =
            stringField(root, "status")
                ?: return reject("missing status; OBSERVED forbidden")
        if (status != "OK") {
            return reject("provider status=$status; OBSERVED forbidden")
        }

        val resultsNode =
            root.map["results"]
                ?: return reject("missing results; OBSERVED forbidden")
        if (resultsNode is ArchiveJson.Null) {
            return reject("results null; OBSERVED forbidden")
        }
        val results =
            try {
                resultsNode.asObject("results")
            } catch (e: ArchiveValidationException) {
                return reject("results must be object: ${e.message}")
            }

        val eventsNode =
            results.map["events"]
                ?: return reject("missing results.events; OBSERVED forbidden")
        if (eventsNode is ArchiveJson.Null) {
            return reject("results.events null; OBSERVED forbidden")
        }
        val eventsArr =
            try {
                eventsNode.asArray("results.events")
            } catch (e: ArchiveValidationException) {
                return reject("results.events must be array: ${e.message}")
            }

        val fields = linkedSetOf("status", "results", "events")
        if (root.map.containsKey("request_id")) fields += "request_id"
        when (val nameNode = results.map["name"]) {
            null, is ArchiveJson.Null -> Unit
            is ArchiveJson.Str -> {
                if (nameNode.value.isNotBlank()) fields += "name"
            }
            else -> return reject("results.name must be string when present")
        }

        val validated = mutableListOf<MassiveTickerChangeRawEvidence>()
        for ((index, item) in eventsArr.items.withIndex()) {
            val eventObj =
                try {
                    item.asObject("results.events[$index]")
                } catch (e: ArchiveValidationException) {
                    return reject("results.events[$index] must be object: ${e.message}")
                }

            val type =
                nonBlankString(eventObj, "type")
                    ?: return reject(
                        "results.events[$index].type missing/blank/wrong type; OBSERVED forbidden",
                    )
            if (type != MassiveTickerEventsArchiveClient.TYPES_TICKER_CHANGE) {
                return reject(
                    "results.events[$index].type=$type; only ticker_change accepted in this PoC",
                )
            }

            val date =
                nonBlankString(eventObj, "date")
                    ?: return reject(
                        "results.events[$index].date missing/blank/wrong type; OBSERVED forbidden",
                    )
            if (!isValidCalendarDate(date)) {
                return reject(
                    "results.events[$index].date invalid calendar date: $date",
                )
            }

            val changeNode =
                eventObj.map["ticker_change"]
                    ?: return reject(
                        "results.events[$index].ticker_change missing; OBSERVED forbidden",
                    )
            if (changeNode is ArchiveJson.Null) {
                return reject(
                    "results.events[$index].ticker_change null; OBSERVED forbidden",
                )
            }
            val changeObj =
                try {
                    changeNode.asObject("results.events[$index].ticker_change")
                } catch (e: ArchiveValidationException) {
                    return reject(
                        "results.events[$index].ticker_change must be object: ${e.message}",
                    )
                }
            val ticker =
                nonBlankString(changeObj, "ticker")
                    ?: return reject(
                        "results.events[$index].ticker_change.ticker " +
                            "missing/blank/wrong type; OBSERVED forbidden",
                    )

            fields += "type"
            fields += "date"
            fields += "ticker_change"
            fields += "ticker_change.ticker"
            validated +=
                MassiveTickerChangeRawEvidence(
                    type = type,
                    date = date,
                    ticker = ticker,
                )
        }

        val auditNotes = mutableListOf<String>()
        auditNotes +=
            "massive Ticker Events ok; raw Ticker Events evidence only; " +
                "lookup id≠SecurityId; ticker_change.ticker≠SecurityId; " +
                "event date≠knownAt; event date≠validFrom/validTo; " +
                "ingestedAt/eligibility≠event date; no continuity inference; " +
                "experimental endpoint; externalIdentifier not auto-set"
        if (validated.isEmpty()) {
            auditNotes +=
                "empty events observed; no-event meaning UNKNOWN; " +
                    "absence≠no ticker change; completeness未証明; negative proof禁止"
        } else {
            if (hasPotentialDuplicateOrConflict(validated)) {
                auditNotes += "potential duplicate/conflict; preserved"
            }
            auditNotes +=
                "raw event order preserved; no sort/dedupe; " +
                    "no old/new invention; no Security continuity derivation"
        }

        return MassiveTickerEventsValidationOutcome(
            okForObserved = true,
            observedFields = fields.toList().sorted(),
            notes = auditNotes.joinToString("; "),
            eventCount = validated.size,
            validatedEvents = validated.toList(),
        )
    }

    private fun hasPotentialDuplicateOrConflict(
        events: List<MassiveTickerChangeRawEvidence>,
    ): Boolean {
        if (events.size < 2) return false
        val exactDup =
            events
                .groupBy { Triple(it.type, it.date, it.ticker) }
                .any { it.value.size > 1 }
        if (exactDup) return true
        val sameDateDifferentTicker =
            events
                .groupBy { it.date }
                .any { (_, group) -> group.map { it.ticker }.distinct().size > 1 }
        return sameDateDifferentTicker
    }

    private fun isValidCalendarDate(value: String): Boolean {
        if (!DATE_SHAPE.matches(value)) return false
        return try {
            LocalDate.parse(value)
            true
        } catch (_: DateTimeParseException) {
            false
        }
    }

    private fun stringField(
        obj: ArchiveJson.Obj,
        key: String,
    ): String? =
        when (val v = obj.map[key]) {
            is ArchiveJson.Str -> v.value
            else -> null
        }

    private fun nonBlankString(
        obj: ArchiveJson.Obj,
        key: String,
    ): String? = stringField(obj, key)?.takeIf { it.isNotBlank() }

    private fun decodeUtf8Strict(bytes: ByteArray): String {
        val decoder =
            StandardCharsets.UTF_8
                .newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
        return decoder.decode(ByteBuffer.wrap(bytes)).toString()
    }

    private fun reject(notes: String) =
        MassiveTickerEventsValidationOutcome(
            okForObserved = false,
            observedFields = emptyList(),
            notes = notes,
            eventCount = 0,
            validatedEvents = emptyList(),
        )

    private val DATE_SHAPE = Regex("^\\d{4}-\\d{2}-\\d{2}$")
}
