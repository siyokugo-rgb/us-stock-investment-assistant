package sec.poc

import java.math.BigDecimal
import java.time.LocalDate
import java.time.format.DateTimeParseException

object SecCompanyFactsParser {
    fun parse(
        jsonText: String,
        retainTags: Set<String>? = null,
    ): SecCompanyFactsDocument {
        val root =
            try {
                SecJson.parse(jsonText).asObject("companyfacts")
            } catch (e: SecEdgarPocException) {
                throw e
            } catch (e: Exception) {
                throw SecEdgarPocException("Malformed CompanyFacts JSON: ${e.message}", e)
            }

        val topKeys = root.map.keys
        val cikRaw = root.required("cik", "companyfacts")
        val cik =
            when (cikRaw) {
                is SecJson.Num -> SecCik.parse(cikRaw.value)
                is SecJson.Str -> SecCik.parse(cikRaw.value)
                else -> throw SecEdgarPocException("companyfacts.cik: expected number or string")
            }
        val entityName = root.required("entityName", "companyfacts").asString("entityName")
        val facts = root.required("facts", "companyfacts").asObject("facts")
        if (facts.map.isEmpty()) {
            throw SecEdgarPocException("companyfacts.facts: unexpected empty taxonomy map")
        }

        val concepts = mutableListOf<SecCompanyFactsConcept>()
        for ((taxonomy, taxNode) in facts.map) {
            val taxObj = taxNode.asObject("facts.$taxonomy")
            for ((tag, conceptNode) in taxObj.map) {
                if (retainTags != null && tag !in retainTags) continue
                concepts += parseConcept(taxonomy, tag, conceptNode.asObject("facts.$taxonomy.$tag"))
            }
        }

        return SecCompanyFactsDocument(
            cik = cik,
            entityName = entityName,
            taxonomies = facts.map.keys,
            concepts = concepts,
            observedTopLevelKeys = topKeys,
        )
    }

    private fun parseConcept(
        taxonomy: String,
        tag: String,
        node: SecJson.Obj,
    ): SecCompanyFactsConcept {
        val label = node.optional("label")?.asStringOrNull()
        val description = node.optional("description")?.asStringOrNull()
        val unitsNode = node.required("units", "facts.$taxonomy.$tag").asObject("units")
        if (unitsNode.map.isEmpty()) {
            throw SecEdgarPocException("facts.$taxonomy.$tag.units: unexpected empty units map")
        }
        val units = linkedMapOf<String, List<SecCompanyFactVersion>>()
        for ((unit, arrNode) in unitsNode.map) {
            val arr = arrNode.asArray("facts.$taxonomy.$tag.units.$unit")
            val versions =
                arr.items.mapIndexed { idx, item ->
                    parseFactVersion(
                        taxonomy = taxonomy,
                        tag = tag,
                        unit = unit,
                        node = item.asObject("facts.$taxonomy.$tag.units.$unit[$idx]"),
                        context = "facts.$taxonomy.$tag.units.$unit[$idx]",
                    )
                }
            units[unit] = versions
        }
        return SecCompanyFactsConcept(
            taxonomy = taxonomy,
            tag = tag,
            label = label,
            description = description,
            units = units,
        )
    }

    private fun parseFactVersion(
        taxonomy: String,
        tag: String,
        unit: String,
        node: SecJson.Obj,
        context: String,
    ): SecCompanyFactVersion {
        val valNode = node.required("val", context)
        val value =
            when (valNode) {
                is SecJson.Num ->
                    try {
                        BigDecimal(valNode.value)
                    } catch (e: NumberFormatException) {
                        throw SecEdgarPocException("$context.val: invalid number '${valNode.value}'", e)
                    }
                is SecJson.Str ->
                    try {
                        BigDecimal(valNode.value)
                    } catch (e: NumberFormatException) {
                        throw SecEdgarPocException("$context.val: invalid number '${valNode.value}'", e)
                    }
                else -> throw SecEdgarPocException("$context.val: expected number")
            }
        val end = parseRequiredDate(node.required("end", context).asString("$context.end"), "$context.end")
        val start =
            node.optional("start")?.asStringOrNull()?.let { parseRequiredDate(it, "$context.start") }
        val fy = node.optional("fy")?.let { parseIntOrNull(it, "$context.fy") }
        val fp = node.optional("fp")?.asStringOrNull()
        val form = node.optional("form")?.asStringOrNull()
        val filed =
            node.optional("filed")?.asStringOrNull()?.let { parseRequiredDate(it, "$context.filed") }
        val frame = node.optional("frame")?.asStringOrNull()
        val accnRaw = node.optional("accn")?.asStringOrNull()
        val accn =
            if (accnRaw.isNullOrBlank()) {
                null
            } else {
                try {
                    SecAccessionNumber.parse(accnRaw)
                } catch (e: SecEdgarPocException) {
                    throw SecEdgarPocException("$context.accn: invalid accession '$accnRaw'", e)
                }
            }
        return SecCompanyFactVersion(
            taxonomy = taxonomy,
            tag = tag,
            unit = unit,
            value = value,
            start = start,
            end = end,
            fy = fy,
            fp = fp,
            form = form,
            filed = filed,
            frame = frame,
            accn = accn,
        )
    }

    private fun parseRequiredDate(
        raw: String,
        context: String,
    ): LocalDate =
        try {
            LocalDate.parse(raw)
        } catch (e: DateTimeParseException) {
            throw SecEdgarPocException("$context: invalid date '$raw'", e)
        }

    private fun parseIntOrNull(
        node: SecJson,
        context: String,
    ): Int? =
        when (node) {
            is SecJson.Null -> null
            is SecJson.Num ->
                node.value.toIntOrNull()
                    ?: throw SecEdgarPocException("$context: invalid int '${node.value}'")
            is SecJson.Str ->
                if (node.value.isBlank()) {
                    null
                } else {
                    node.value.toIntOrNull()
                        ?: throw SecEdgarPocException("$context: invalid int '${node.value}'")
                }
            else -> throw SecEdgarPocException("$context: expected int")
        }

    fun assessKnownAt(): List<Pair<String, SecCompanyFactsKnownAtGrade>> =
        listOf(
            "filed" to SecCompanyFactsKnownAtGrade.UNUSABLE_AS_KNOWN_AT,
            "CompanyFacts alone for historical decisionAt" to
                SecCompanyFactsKnownAtGrade.PARTIAL_REQUIRES_ACCESSION_JOIN,
        )
}
