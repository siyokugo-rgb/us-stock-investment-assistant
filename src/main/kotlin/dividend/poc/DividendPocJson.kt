package dividend.poc

/**
 * Dividend PoC 用の最小 JSON パーサ。推測補完しない。
 */
internal sealed class DividendPocJson {
    data class Obj(val map: Map<String, DividendPocJson>) : DividendPocJson()

    data class Arr(val items: List<DividendPocJson>) : DividendPocJson()

    data class Str(val value: String) : DividendPocJson()

    data class Num(val value: String) : DividendPocJson()

    data class Bool(val value: Boolean) : DividendPocJson()

    data object Null : DividendPocJson()

    fun asObject(context: String): Obj =
        this as? Obj ?: throw AlphaVantageDividendPocException("$context: expected object")

    fun asArray(context: String): Arr =
        this as? Arr ?: throw AlphaVantageDividendPocException("$context: expected array")

    fun asString(context: String): String =
        when (this) {
            is Str -> value
            is Num -> value
            is Null -> throw AlphaVantageDividendPocException("$context: expected string, got null")
            else -> throw AlphaVantageDividendPocException("$context: expected string")
        }

    fun optional(key: String): DividendPocJson? = (this as? Obj)?.map?.get(key)

    fun required(
        key: String,
        context: String,
    ): DividendPocJson =
        asObject(context).map[key]
            ?: throw AlphaVantageDividendPocException("$context: missing required field '$key'")

    companion object {
        fun parse(text: String): DividendPocJson {
            val parser = Parser(text)
            val value = parser.parseValue()
            parser.skipWs()
            if (!parser.eof()) {
                throw AlphaVantageDividendPocException("Trailing content in JSON at index ${parser.index}")
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

        fun parseValue(): DividendPocJson {
            skipWs()
            if (eof()) throw AlphaVantageDividendPocException("Unexpected end of JSON")
            return when (val c = text[index]) {
                '{' -> parseObject()
                '[' -> parseArray()
                '"' -> Str(parseString())
                't' -> parseLiteral("true", Bool(true))
                'f' -> parseLiteral("false", Bool(false))
                'n' -> parseLiteral("null", Null)
                '-', in '0'..'9' -> Num(parseNumber())
                else -> throw AlphaVantageDividendPocException("Unexpected character '$c' at $index")
            }
        }

        private fun parseObject(): Obj {
            expect('{')
            skipWs()
            if (peek('}')) {
                index++
                return Obj(emptyMap())
            }
            val map = linkedMapOf<String, DividendPocJson>()
            while (true) {
                skipWs()
                val key = parseString()
                skipWs()
                expect(':')
                val value = parseValue()
                if (map.put(key, value) != null) {
                    throw AlphaVantageDividendPocException("Duplicate JSON key '$key'")
                }
                skipWs()
                when {
                    peek('}') -> {
                        index++
                        break
                    }
                    peek(',') -> index++
                    else -> throw AlphaVantageDividendPocException("Expected ',' or '}' at $index")
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
            val items = mutableListOf<DividendPocJson>()
            while (true) {
                items += parseValue()
                skipWs()
                when {
                    peek(']') -> {
                        index++
                        break
                    }
                    peek(',') -> index++
                    else -> throw AlphaVantageDividendPocException("Expected ',' or ']' at $index")
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
                        if (eof()) throw AlphaVantageDividendPocException("Unterminated escape")
                        when (val e = text[index++]) {
                            '"', '\\', '/' -> sb.append(e)
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000c')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                if (index + 4 > text.length) {
                                    throw AlphaVantageDividendPocException("Bad unicode escape")
                                }
                                val hex = text.substring(index, index + 4)
                                sb.append(hex.toInt(16).toChar())
                                index += 4
                            }
                            else -> throw AlphaVantageDividendPocException("Bad escape '\\$e'")
                        }
                    }
                    else -> sb.append(c)
                }
            }
            throw AlphaVantageDividendPocException("Unterminated string")
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
            value: DividendPocJson,
        ): DividendPocJson {
            if (!text.startsWith(literal, index)) {
                throw AlphaVantageDividendPocException("Expected '$literal' at $index")
            }
            index += literal.length
            return value
        }

        private fun expect(c: Char) {
            skipWs()
            if (eof() || text[index] != c) {
                throw AlphaVantageDividendPocException("Expected '$c' at $index")
            }
            index++
        }

        private fun peek(c: Char): Boolean = !eof() && text[index] == c
    }
}
