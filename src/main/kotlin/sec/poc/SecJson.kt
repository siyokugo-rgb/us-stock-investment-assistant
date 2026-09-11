package sec.poc

/**
 * PoC 用の最小 JSON パーサ。SEC submissions のオブジェクト/配列/スカラーを扱う。
 * schema 逸脱は Fail-Closed（推測補完しない）。
 */
internal sealed class SecJson {
    data class Obj(val map: Map<String, SecJson>) : SecJson()
    data class Arr(val items: List<SecJson>) : SecJson()
    data class Str(val value: String) : SecJson()
    data class Num(val value: String) : SecJson()
    data class Bool(val value: Boolean) : SecJson()
    data object Null : SecJson()

    fun asObject(context: String): Obj =
        this as? Obj ?: throw SecEdgarPocException("$context: expected object")

    fun asArray(context: String): Arr =
        this as? Arr ?: throw SecEdgarPocException("$context: expected array")

    fun asString(context: String): String =
        when (this) {
            is Str -> value
            is Num -> value
            is Null -> throw SecEdgarPocException("$context: expected string, got null")
            else -> throw SecEdgarPocException("$context: expected string")
        }

    fun asStringOrNull(): String? =
        when (this) {
            is Str -> value
            is Num -> value
            is Null -> null
            else -> null
        }

    fun optional(key: String): SecJson? = (this as? Obj)?.map?.get(key)

    fun required(key: String, context: String): SecJson =
        asObject(context).map[key]
            ?: throw SecEdgarPocException("$context: missing required field '$key'")

    companion object {
        fun parse(text: String): SecJson {
            val parser = Parser(text)
            val value = parser.parseValue()
            parser.skipWs()
            if (!parser.eof()) {
                throw SecEdgarPocException("Trailing content in JSON at index ${parser.index}")
            }
            return value
        }
    }

    private class Parser(private val text: String) {
        var index: Int = 0

        fun eof(): Boolean = index >= text.length

        fun skipWs() {
            while (!eof() && text[index].isWhitespace()) index++
        }

        fun parseValue(): SecJson {
            skipWs()
            if (eof()) throw SecEdgarPocException("Unexpected end of JSON")
            return when (val c = text[index]) {
                '{' -> parseObject()
                '[' -> parseArray()
                '"' -> Str(parseString())
                't' -> parseLiteral("true", Bool(true))
                'f' -> parseLiteral("false", Bool(false))
                'n' -> parseLiteral("null", Null)
                '-', in '0'..'9' -> Num(parseNumber())
                else -> throw SecEdgarPocException("Unexpected character '$c' at $index")
            }
        }

        private fun parseObject(): Obj {
            expect('{')
            skipWs()
            if (peek('}')) {
                index++
                return Obj(emptyMap())
            }
            val map = linkedMapOf<String, SecJson>()
            while (true) {
                skipWs()
                if (!peek('"')) throw SecEdgarPocException("Expected string key at $index")
                val key = parseString()
                skipWs()
                expect(':')
                map[key] = parseValue()
                skipWs()
                when {
                    peek('}') -> {
                        index++
                        return Obj(map)
                    }
                    peek(',') -> index++
                    else -> throw SecEdgarPocException("Expected ',' or '}' at $index")
                }
            }
        }

        private fun parseArray(): Arr {
            expect('[')
            skipWs()
            if (peek(']')) {
                index++
                return Arr(emptyList())
            }
            val items = mutableListOf<SecJson>()
            while (true) {
                items += parseValue()
                skipWs()
                when {
                    peek(']') -> {
                        index++
                        return Arr(items)
                    }
                    peek(',') -> index++
                    else -> throw SecEdgarPocException("Expected ',' or ']' at $index")
                }
            }
        }

        private fun parseString(): String {
            expect('"')
            val sb = StringBuilder()
            while (!eof()) {
                when (val c = text[index++]) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        if (eof()) throw SecEdgarPocException("Unterminated escape")
                        when (val e = text[index++]) {
                            '"', '\\', '/' -> sb.append(e)
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                if (index + 4 > text.length) throw SecEdgarPocException("Bad unicode escape")
                                val hex = text.substring(index, index + 4)
                                sb.append(hex.toInt(16).toChar())
                                index += 4
                            }
                            else -> throw SecEdgarPocException("Invalid escape '\\$e'")
                        }
                    }
                    else -> sb.append(c)
                }
            }
            throw SecEdgarPocException("Unterminated string")
        }

        private fun parseNumber(): String {
            val start = index
            if (rawPeek('-')) index++
            if (rawPeek('0')) {
                index++
            } else {
                if (eof() || text[index] !in '1'..'9') throw SecEdgarPocException("Invalid number at $start")
                while (!eof() && text[index].isDigit()) index++
            }
            if (rawPeek('.')) {
                index++
                if (eof() || !text[index].isDigit()) throw SecEdgarPocException("Invalid fraction")
                while (!eof() && text[index].isDigit()) index++
            }
            if (rawPeek('e') || rawPeek('E')) {
                index++
                if (rawPeek('+') || rawPeek('-')) index++
                if (eof() || !text[index].isDigit()) throw SecEdgarPocException("Invalid exponent")
                while (!eof() && text[index].isDigit()) index++
            }
            return text.substring(start, index)
        }

        private fun parseLiteral(literal: String, value: SecJson): SecJson {
            if (text.regionMatches(index, literal, 0, literal.length)) {
                index += literal.length
                return value
            }
            throw SecEdgarPocException("Expected '$literal' at $index")
        }

        private fun expect(c: Char) {
            skipWs()
            if (eof() || text[index] != c) throw SecEdgarPocException("Expected '$c' at $index")
            index++
        }

        private fun peek(c: Char): Boolean {
            skipWs()
            return !eof() && text[index] == c
        }

        /** Peek without skipping whitespace (required inside number token scanning). */
        private fun rawPeek(c: Char): Boolean = !eof() && text[index] == c
    }
}
