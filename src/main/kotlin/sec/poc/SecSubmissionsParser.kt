package sec.poc

import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeParseException

object SecSubmissionsParser {
    fun parse(jsonText: String): SecSubmissionsDocument {
        val root =
            try {
                SecJson.parse(jsonText).asObject("submissions")
            } catch (e: SecEdgarPocException) {
                throw e
            } catch (e: Exception) {
                throw SecEdgarPocException("Malformed JSON: ${e.message}", e)
            }

        val topKeys = root.map.keys
        val cik = SecCik.parse(root.required("cik", "submissions").asString("cik"))
        val name = root.required("name", "submissions").asString("name")
        val entityType = root.optional("entityType")?.asStringOrNull()
        val tickers = stringList(root.optional("tickers"), "tickers")
        val exchanges = stringList(root.optional("exchanges"), "exchanges")
        val formerNames = parseFormerNames(root.optional("formerNames"))

        val filings = root.required("filings", "submissions").asObject("filings")
        val recent = filings.required("recent", "filings").asObject("filings.recent")
        val recentKeys = recent.map.keys
        val recentFilings = parseRecentFilings(recent)
        val historyFiles = parseHistoryFiles(filings.optional("files"))

        return SecSubmissionsDocument(
            cik = cik,
            name = name,
            entityType = entityType,
            tickers = tickers,
            exchanges = exchanges,
            formerNames = formerNames,
            recentFilings = recentFilings,
            historyFiles = historyFiles,
            observedTopLevelKeys = topKeys,
            observedRecentKeys = recentKeys,
        )
    }

    fun assessTimestampFields(): List<SecTimestampAssessment> =
        listOf(
            SecTimestampAssessment(
                field = "filingDate",
                meaning = "提出日（カレンダー日）。時刻成分を持たない。",
                gradeForHistoricalKnownAt = PitEvidenceGrade.PARTIAL,
                notes = "日付境界までは絞り込めるが、その日のどの瞬間に public になったかは示さない。exact knownAt には不足。",
            ),
            SecTimestampAssessment(
                field = "reportDate",
                meaning = "報告対象期間・事象日（period of report）。",
                gradeForHistoricalKnownAt = PitEvidenceGrade.UNUSABLE,
                notes = "fiscal/period の意味であり、公開利用可能時刻ではない。decisionAt 可用性判定に使ってはならない。",
            ),
            SecTimestampAssessment(
                field = "acceptanceDateTime",
                meaning = "EDGAR が filing を accept した時刻。",
                gradeForHistoricalKnownAt = PitEvidenceGrade.PARTIAL,
                notes = "acceptance は取得できるが、sec.gov で最初に public dissemination された正確な時刻と常に同一とは限らない。" +
                    " lower-bound / conservative proxy 候補にはなり得るが、CONFIRMED knownAt へ無条件固定してはならない。" +
                    " 古い行では日付のみを 05:00:00.000Z 等へ丸めた値も観察され、精度が一様でない。",
            ),
            SecTimestampAssessment(
                field = "ingestedAt",
                meaning = "本アプリが当該 payload を取得した時刻。",
                gradeForHistoricalKnownAt = PitEvidenceGrade.UNUSABLE,
                notes = "運用上の保有PITには使う。historical knowledge PIT の knownAt 代理には使わない。",
            ),
            SecTimestampAssessment(
                field = "sec.gov first public dissemination",
                meaning = "一般利用者が sec.gov 上で最初に利用可能になった時刻。",
                gradeForHistoricalKnownAt = PitEvidenceGrade.UNVERIFIED,
                notes = "本 submissions JSON だけでは常時提供を確認できなかった。別証跡が無い限り CONFIRMED にできない。",
            ),
        )

    private fun parseRecentFilings(recent: SecJson.Obj): List<SecFilingRecord> {
        val accession = requiredStringColumn(recent, "accessionNumber")
        val form = requiredStringColumn(recent, "form")
        val filingDate = optionalStringColumn(recent, "filingDate")
        val reportDate = optionalStringColumn(recent, "reportDate")
        val acceptance = optionalStringColumn(recent, "acceptanceDateTime")
        val primaryDocument = optionalStringColumn(recent, "primaryDocument")
        val primaryDocDescription = optionalStringColumn(recent, "primaryDocDescription")
        val isXBRL = optionalBoolColumn(recent, "isXBRL")
        val isInlineXBRL = optionalBoolColumn(recent, "isInlineXBRL")

        val n = accession.size
        fun checkLen(size: Int, name: String) {
            if (size != n) {
                throw SecEdgarPocException(
                    "filings.recent columnar length mismatch: accessionNumber=$n, $name=$size",
                )
            }
        }
        checkLen(form.size, "form")
        checkLen(filingDate.size, "filingDate")
        checkLen(reportDate.size, "reportDate")
        checkLen(acceptance.size, "acceptanceDateTime")
        checkLen(primaryDocument.size, "primaryDocument")
        checkLen(primaryDocDescription.size, "primaryDocDescription")
        checkLen(isXBRL.size, "isXBRL")
        checkLen(isInlineXBRL.size, "isInlineXBRL")

        return (0 until n).map { i ->
            val acceptanceRaw = acceptance[i].ifBlank { null }
            SecFilingRecord(
                accessionNumber = accession[i],
                form = form[i],
                filingDate = parseDateOrNull(filingDate[i], "filingDate"),
                reportDate = parseDateOrNull(reportDate[i], "reportDate"),
                acceptanceDateTime = parseInstantOrNull(acceptanceRaw),
                acceptanceDateTimeRaw = acceptanceRaw,
                primaryDocument = primaryDocument[i].ifBlank { null },
                primaryDocDescription = primaryDocDescription[i].ifBlank { null },
                isXBRL = isXBRL[i],
                isInlineXBRL = isInlineXBRL[i],
            )
        }
    }

    private fun parseHistoryFiles(node: SecJson?): List<SecHistoryFileRef> {
        if (node == null || node is SecJson.Null) return emptyList()
        val arr = node.asArray("filings.files")
        return arr.items.mapIndexed { idx, item ->
            val obj = item.asObject("filings.files[$idx]")
            SecHistoryFileRef(
                name = obj.required("name", "filings.files[$idx]").asString("name"),
                filingCount = obj.optional("filingCount")?.let { parseInt(it, "filingCount") },
                filingFrom = obj.optional("filingFrom")?.asStringOrNull()?.let { parseDateOrNull(it, "filingFrom") },
                filingTo = obj.optional("filingTo")?.asStringOrNull()?.let { parseDateOrNull(it, "filingTo") },
            )
        }
    }

    private fun parseFormerNames(node: SecJson?): List<SecFormerName> {
        if (node == null || node is SecJson.Null) return emptyList()
        val arr = node.asArray("formerNames")
        return arr.items.mapIndexed { idx, item ->
            val obj = item.asObject("formerNames[$idx]")
            SecFormerName(
                name = obj.required("name", "formerNames[$idx]").asString("name"),
                from = obj.optional("from")?.asStringOrNull(),
                to = obj.optional("to")?.asStringOrNull(),
            )
        }
    }

    private fun stringList(node: SecJson?, context: String): List<String> {
        if (node == null || node is SecJson.Null) return emptyList()
        return node.asArray(context).items.mapIndexed { i, v -> v.asString("$context[$i]") }
    }

    private fun requiredStringColumn(recent: SecJson.Obj, key: String): List<String> {
        val arr =
            recent.map[key]?.asArray("filings.recent.$key")
                ?: throw SecEdgarPocException("filings.recent: missing required column '$key'")
        return arr.items.mapIndexed { i, v ->
            when (v) {
                is SecJson.Str -> v.value
                is SecJson.Num -> v.value
                is SecJson.Null -> throw SecEdgarPocException("filings.recent.$key[$i] must not be null")
                else -> throw SecEdgarPocException("filings.recent.$key[$i] must be string")
            }
        }
    }

    private fun optionalStringColumn(recent: SecJson.Obj, key: String): List<String> {
        val arr =
            recent.map[key]?.asArray("filings.recent.$key")
                ?: throw SecEdgarPocException("filings.recent: missing expected column '$key'")
        return arr.items.mapIndexed { i, v ->
            when (v) {
                is SecJson.Str -> v.value
                is SecJson.Num -> v.value
                is SecJson.Null -> ""
                else -> throw SecEdgarPocException("filings.recent.$key[$i] must be string or null")
            }
        }
    }

    private fun optionalBoolColumn(recent: SecJson.Obj, key: String): List<Boolean?> {
        val arr =
            recent.map[key]?.asArray("filings.recent.$key")
                ?: throw SecEdgarPocException("filings.recent: missing expected column '$key'")
        return arr.items.mapIndexed { i, v ->
            when (v) {
                is SecJson.Bool -> v.value
                is SecJson.Num ->
                    when (v.value.toIntOrNull()) {
                        0 -> false
                        1 -> true
                        else ->
                            throw SecEdgarPocException(
                                "filings.recent.$key[$i] invalid bool number ${v.value}",
                            )
                    }
                is SecJson.Str ->
                    when (v.value.trim().lowercase()) {
                        "0", "false" -> false
                        "1", "true" -> true
                        else ->
                            throw SecEdgarPocException(
                                "filings.recent.$key[$i] invalid bool string ${v.value}",
                            )
                    }
                is SecJson.Null -> null
                else -> throw SecEdgarPocException("filings.recent.$key[$i] must be boolean")
            }
        }
    }

    private fun parseDateOrNull(raw: String, field: String): LocalDate? {
        if (raw.isBlank()) return null
        return try {
            LocalDate.parse(raw)
        } catch (e: DateTimeParseException) {
            throw SecEdgarPocException("Invalid $field date: '$raw'", e)
        }
    }

    private fun parseInstantOrNull(raw: String?): Instant? {
        if (raw.isNullOrBlank()) return null
        return try {
            Instant.parse(raw)
        } catch (e: DateTimeParseException) {
            throw SecEdgarPocException("Invalid acceptanceDateTime: '$raw'", e)
        }
    }

    private fun parseInt(node: SecJson, context: String): Int =
        when (node) {
            is SecJson.Num -> node.value.toInt()
            is SecJson.Str -> node.value.toInt()
            else -> throw SecEdgarPocException("$context: expected number")
        }
}
