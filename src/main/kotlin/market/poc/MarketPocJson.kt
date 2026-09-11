package market.poc

/**
 * Price PoC 用の最小 JSON パーサ。推測補完しない。
 */
internal sealed class MarketPocJson {
    data class Obj(val map: Map<String, MarketPocJson>) : MarketPocJson()

    data class Arr(val items: List<MarketPocJson>) : MarketPocJson()

    data class Str(val value: String) : MarketPocJson()

    data class Num(val value: String) : MarketPocJson()

    data class Bool(val value: Boolean) : MarketPocJson()

    data object Null : MarketPocJson()

    fun asObject(context: String): Obj =
        this as? Obj ?: throw AlphaVantagePocException("$context: expected object")

    fun asString(context: String): String =
        when (this) {
            is Str -> value
            is Num -> value
            is Null -> throw AlphaVantagePocException("$context: expected string, got null")
            else -> throw AlphaVantagePocException("$context: expected string")
        }

    fun optional(key: String): MarketPocJson? = (this as? Obj)?.map?.get(key)

    fun required(
        key: String,
        context: String,
    ): MarketPocJson =
        asObject(context).map[key]
            ?: throw AlphaVantagePocException("$context: missing required field '$key'")

    companion object {
        fun parse(text: String): MarketPocJson {
            val parser = Parser(text)
            val value = parser.parseValue()
            parser.skipWs()
            if (!parser.eof()) {
                throw AlphaVantagePocException("Trailing content in JSON at index ${parser.index}")
            }
            return value
        }
    }

    private class Parser(
        private val text: String,
    ) {
        var index: Int = 0

        fun eof(): Boolean = index >= text.length

        fun skipWs() {
            while (!eof() && text[index].isWhitespace()) index++
        }

        fun parseValue(): MarketPocJson {
            skipWs()
            if (eof()) throw AlphaVantagePocException("Unexpected end of JSON")
            return when (val c = text[index]) {
                '{' -> parseObject()
                '[' -> parseArray()
                '"' -> Str(parseString())
                't' -> parseLiteral("true", Bool(true))
                'f' -> parseLiteral("false", Bool(false))
                'n' -> parseLiteral("null", Null)
                '-', in '0'..'9' -> Num(parseNumber())
                else -> throw AlphaVantagePocException("Unexpected character '$c' at $index")
            }
        }

        private fun parseObject(): Obj {
            expect('{')
            skipWs()
            if (peek('}')) {
                index++
                return Obj(emptyMap())
            }
            val map = linkedMapOf<String, MarketPocJson>()
            while (true) {
                skipWs()
                val key = parseString()
                skipWs()
                expect(':')
                val value = parseValue()
                if (map.put(key, value) != null) {
                    throw AlphaVantagePocException("Duplicate JSON key '$key'")
                }
                skipWs()
                when {
                    peek('}') -> {
                        index++
                        break
                    }
                    peek(',') -> index++
                    else -> throw AlphaVantagePocException("Expected ',' or '}' at $index")
                }
            }
            return Obj(map)
        }

        private fun parseArray(): Arr {
            expect('[')
            skipWs()
            if (peek(']')) {
                index++
                return Arr(emptyList())
            }
            val items = mutableListOf<MarketPocJson>()
            while (true) {
                items += parseValue()
                skipWs()
                when {
                    peek(']') -> {
                        index++
                        break
                    }
                    peek(',') -> index++
                    else -> throw AlphaVantagePocException("Expected ',' or ']' at $index")
                }
            }
            return Arr(items)
        }

        private fun parseString(): String {
            expect('"')
            val sb = StringBuilder()
            while (!eof()) {
                when (val c = text[index++]) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        if (eof()) throw AlphaVantagePocException("Unterminated escape")
                        when (val e = text[index++]) {
                            '"', '\\', '/' -> sb.append(e)
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000c')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                if (index + 4 > text.length) {
                                    throw AlphaVantagePocException("Bad unicode escape")
                                }
                                val hex = text.substring(index, index + 4)
                                sb.append(hex.toInt(16).toChar())
                                index += 4
                            }
                            else -> throw AlphaVantagePocException("Bad escape '\\$e'")
                        }
                    }
                    else -> sb.append(c)
                }
            }
            throw AlphaVantagePocException("Unterminated string")
        }

        private fun parseNumber(): String {
            val start = index
            if (peek('-')) index++
            while (!eof() && text[index] in '0'..'9') index++
            if (peek('.')) {
                index++
                while (!eof() && text[index] in '0'..'9') index++
            }
            if (!eof() && (text[index] == 'e' || text[index] == 'E')) {
                index++
                if (!eof() && (text[index] == '+' || text[index] == '-')) index++
                while (!eof() && text[index] in '0'..'9') index++
            }
            return text.substring(start, index)
        }

        private fun parseLiteral(
            literal: String,
            value: MarketPocJson,
        ): MarketPocJson {
            if (!text.startsWith(literal, index)) {
                throw AlphaVantagePocException("Expected '$literal' at $index")
            }
            index += literal.length
            return value
        }

        private fun expect(c: Char) {
            skipWs()
            if (eof() || text[index] != c) {
                throw AlphaVantagePocException("Expected '$c' at $index")
            }
            index++
        }

        private fun peek(c: Char): Boolean = !eof() && text[index] == c
    }
}
