package dividend.poc

import java.math.BigDecimal
import java.security.MessageDigest
import java.time.LocalDate
import java.time.format.DateTimeParseException

// DateTimeParseException is used for Fail-Closed date parsing.

object AlphaVantageDividendParser {
    private const val KEY_SYMBOL = "symbol"
    private const val KEY_DATA = "data"
    private const val KEY_EX = "ex_dividend_date"
    private const val KEY_DECLARATION = "declaration_date"
    private const val KEY_RECORD = "record_date"
    private const val KEY_PAYMENT = "payment_date"
    private const val KEY_AMOUNT = "amount"

    fun parse(
        jsonText: String,
        requestedSymbol: String,
    ): AlphaVantageDividendRawSeries {
        if (jsonText.isBlank()) {
            throw AlphaVantageDividendPocException("Empty Alpha Vantage body")
        }
        val root =
            try {
                DividendPocJson.parse(jsonText).asObject("alphavantage")
            } catch (e: AlphaVantageDividendPocException) {
                throw e
            } catch (e: Exception) {
                throw AlphaVantageDividendPocException("Malformed Alpha Vantage JSON: ${e.message}", e)
            }

        rejectProviderEnvelope(root)

        val symbol = root.required(KEY_SYMBOL, "alphavantage").asString(KEY_SYMBOL).trim()
        if (!symbol.equals(requestedSymbol.trim(), ignoreCase = true)) {
            throw AlphaVantageDividendPocException(
                "Requested symbol mismatch: requested='$requestedSymbol', payload='$symbol'",
            )
        }

        val dataNode = root.required(KEY_DATA, "alphavantage").asArray(KEY_DATA)
        val events =
            dataNode.items.mapIndexed { index, item ->
                parseEvent(
                    providerSymbol = symbol,
                    node = item.asObject("$KEY_DATA[$index]"),
                    index = index,
                )
            }

        return AlphaVantageDividendRawSeries(
            providerSymbol = symbol,
            events = events,
            historicalKnownAtStatus = HistoricalKnownAtStatus.UNRESOLVED_UNUSABLE,
            currencyResolutionStatus = CurrencyResolutionStatus.UNRESOLVED_FROM_DIVIDENDS,
            dividendTypeResolutionStatus = DividendTypeResolutionStatus.UNRESOLVED_FROM_DIVIDENDS,
            securityIdMappingStatus = SecurityIdMappingStatus.FORBIDDEN_FROM_PROVIDER_SYMBOL,
        )
    }

    private fun rejectProviderEnvelope(root: DividendPocJson.Obj) {
        fun rejectIfPresent(key: String) {
            val value = root.optional(key)?.asString(key)
            if (!value.isNullOrBlank()) {
                throw AlphaVantageDividendPocException("Alpha Vantage $key envelope: $value")
            }
        }
        // Align with Alpha Vantage / price PoC envelope keys.
        rejectIfPresent("Error Message")
        rejectIfPresent("Information")
        rejectIfPresent("Note")

        if (!root.map.containsKey(KEY_SYMBOL) || !root.map.containsKey(KEY_DATA)) {
            throw AlphaVantageDividendPocException(
                "Expected '$KEY_SYMBOL' and '$KEY_DATA'; keys=${root.map.keys}",
            )
        }
    }

    private fun parseEvent(
        providerSymbol: String,
        node: DividendPocJson.Obj,
        index: Int,
    ): AlphaVantageDividendRawEvent {
        val context = "data[$index]"
        val exRaw = node.required(KEY_EX, context).asString(KEY_EX)
        val exDate = parseRequiredDate(exRaw, KEY_EX, context)

        val declarationRaw = optionalRawString(node, KEY_DECLARATION)
        val recordRaw = optionalRawString(node, KEY_RECORD)
        val paymentRaw = optionalRawString(node, KEY_PAYMENT)

        val amountRaw = node.required(KEY_AMOUNT, context).asString(KEY_AMOUNT).trim()
        if (amountRaw.isEmpty() || isAbsentSentinel(amountRaw)) {
            throw AlphaVantageDividendPocException("$context.$KEY_AMOUNT is missing/sentinel: '$amountRaw'")
        }
        val amount = parseAmount(amountRaw, "$context.$KEY_AMOUNT")
        if (amount.signum() < 0) {
            throw AlphaVantageDividendPocException("$context.$KEY_AMOUNT must not be negative: $amountRaw")
        }

        return AlphaVantageDividendRawEvent(
            providerSymbol = providerSymbol,
            exDividendDate = exDate,
            declarationDate = parseOptionalDate(declarationRaw, KEY_DECLARATION, context),
            recordDate = parseOptionalDate(recordRaw, KEY_RECORD, context),
            paymentDate = parseOptionalDate(paymentRaw, KEY_PAYMENT, context),
            amount = amount,
            declarationDateRaw = declarationRaw,
            recordDateRaw = recordRaw,
            paymentDateRaw = paymentRaw,
            amountRaw = amountRaw,
        )
    }

    private fun optionalRawString(
        node: DividendPocJson.Obj,
        key: String,
    ): String? {
        val value = node.optional(key) ?: return null
        return when (value) {
            is DividendPocJson.Null -> null
            else -> value.asString(key)
        }
    }

    private fun parseRequiredDate(
        raw: String,
        field: String,
        context: String,
    ): LocalDate {
        val trimmed = raw.trim()
        if (trimmed.isEmpty() || isAbsentSentinel(trimmed)) {
            throw AlphaVantageDividendPocException("$context.$field is required but sentinel/empty: '$raw'")
        }
        return try {
            LocalDate.parse(trimmed)
        } catch (e: DateTimeParseException) {
            throw AlphaVantageDividendPocException("$context.$field malformed date: '$raw'", e)
        }
    }

    private fun parseOptionalDate(
        raw: String?,
        field: String,
        context: String,
    ): LocalDate? {
        // field absent / JSON null arrive as raw == null → LocalDate? null (no invention).
        if (raw == null) return null
        val trimmed = raw.trim()
        // Live IBM DIVIDENDS evidence: optional dates use the string sentinel "None".
        if (isAbsentSentinel(trimmed)) {
            return null
        }
        // Unconfirmed placeholders (empty, "null", "N/A", "0000-00-00", etc.) are Fail-Closed:
        // do not silently normalize unknown provider values into missing dates.
        if (trimmed.isEmpty()) {
            throw AlphaVantageDividendPocException(
                "$context.$field empty string is not a confirmed DIVIDENDS absence sentinel",
            )
        }
        return try {
            LocalDate.parse(trimmed)
        } catch (e: DateTimeParseException) {
            throw AlphaVantageDividendPocException("$context.$field malformed date: '$raw'", e)
        }
    }

    /**
     * Confirmed DIVIDENDS optional-date absence sentinel from live IBM demo payload
     * (and official sample shape using the same key set): exact string `"None"`.
     *
     * Not accepted without primary evidence: empty string, `"null"`, `"N/A"`, `"0000-00-00"`.
     * Field absent / JSON null are handled separately (raw == null) and are not string sentinels.
     */
    fun isAbsentSentinel(raw: String): Boolean = raw.trim() == "None"

    private fun parseAmount(
        raw: String,
        context: String,
    ): BigDecimal =
        try {
            BigDecimal(raw.trim())
        } catch (e: NumberFormatException) {
            throw AlphaVantageDividendPocException("$context: invalid decimal '$raw'", e)
        }

    fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { b -> "%02x".format(b) }
    }
}
