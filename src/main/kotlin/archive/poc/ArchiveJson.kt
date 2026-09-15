package archive.poc

/**
 * Minimal JSON parser for archive PoC validation / manifest.
 * Fail-Closed: no silent coercion.
 */
internal sealed class ArchiveJson {
    data class Obj(val map: Map<String, ArchiveJson>) : ArchiveJson()

    data class Arr(val items: List<ArchiveJson>) : ArchiveJson()

    data class Str(val value: String) : ArchiveJson()

    data class Num(val value: String) : ArchiveJson()

    data class Bool(val value: Boolean) : ArchiveJson()

    data object Null : ArchiveJson()

    fun asArray(context: String): Arr =
        this as? Arr ?: throw ArchiveValidationException("$context: expected JSON array")

    fun asObject(context: String): Obj =
        this as? Obj ?: throw ArchiveValidationException("$context: expected JSON object")

    /** Alias kept for call-site clarity in validators. */
    fun expectArray(context: String): Arr = asArray(context)

    fun expectObject(context: String): Obj = asObject(context)

    companion object {
        fun parse(text: String): ArchiveJson {
            val parser = Parser(text)
            val value = parser.parseValue()
            parser.skipWs()
            if (!parser.eof()) {
                throw ArchiveValidationException("Trailing content in JSON at index ${parser.index}")
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

        fun parseValue(): ArchiveJson {
            skipWs()
            if (eof()) throw ArchiveValidationException("Unexpected end of JSON")
            return when (val c = text[index]) {
                '{' -> parseObject()
                '[' -> parseArray()
                '"' -> Str(parseString())
                't' -> parseLiteral("true", Bool(true))
                'f' -> parseLiteral("false", Bool(false))
                'n' -> parseLiteral("null", Null)
                '-', in '0'..'9' -> Num(parseNumber())
                else -> throw ArchiveValidationException("Unexpected character '$c' at $index")
            }
        }

        private fun parseObject(): Obj {
            expect('{')
            skipWs()
            if (peek('}')) {
                index++
                return Obj(emptyMap())
            }
            val map = linkedMapOf<String, ArchiveJson>()
            while (true) {
                skipWs()
                val key = parseString()
                skipWs()
                expect(':')
                val value = parseValue()
                if (map.containsKey(key)) {
                    throw ArchiveValidationException("Duplicate object key '$key'")
                }
                map[key] = value
                skipWs()
                when {
                    peek(',') -> {
                        index++
                        continue
                    }
                    peek('}') -> {
                        index++
                        break
                    }
                    else -> throw ArchiveValidationException("Expected ',' or '}' at $index")
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
            val items = mutableListOf<ArchiveJson>()
            while (true) {
                items += parseValue()
                skipWs()
                when {
                    peek(',') -> {
                        index++
                        continue
                    }
                    peek(']') -> {
                        index++
                        break
                    }
                    else -> throw ArchiveValidationException("Expected ',' or ']' at $index")
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
                        if (eof()) throw ArchiveValidationException("Unterminated escape")
                        when (val e = text[index++]) {
                            '"', '\\', '/' -> sb.append(e)
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000c')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                if (index + 4 > text.length) {
                                    throw ArchiveValidationException("Bad unicode escape")
                                }
                                val hex = text.substring(index, index + 4)
                                sb.append(hex.toInt(16).toChar())
                                index += 4
                            }
                            else -> throw ArchiveValidationException("Bad escape '\\$e'")
                        }
                    }
                    else -> sb.append(c)
                }
            }
            throw ArchiveValidationException("Unterminated string")
        }

        private fun parseNumber(): String {
            val start = index
            if (peek('-')) index++
            if (peek('0')) {
                index++
            } else {
                if (!peekIn('1'..'9')) throw ArchiveValidationException("Invalid number at $index")
                while (peekIn('0'..'9')) index++
            }
            if (peek('.')) {
                index++
                if (!peekIn('0'..'9')) throw ArchiveValidationException("Invalid fraction at $index")
                while (peekIn('0'..'9')) index++
            }
            if (peek('e') || peek('E')) {
                index++
                if (peek('+') || peek('-')) index++
                if (!peekIn('0'..'9')) throw ArchiveValidationException("Invalid exponent at $index")
                while (peekIn('0'..'9')) index++
            }
            return text.substring(start, index)
        }

        private fun parseLiteral(
            literal: String,
            value: ArchiveJson,
        ): ArchiveJson {
            if (!text.startsWith(literal, index)) {
                throw ArchiveValidationException("Expected '$literal' at $index")
            }
            index += literal.length
            return value
        }

        private fun expect(c: Char) {
            skipWs()
            if (eof() || text[index] != c) {
                throw ArchiveValidationException("Expected '$c' at $index")
            }
            index++
        }

        private fun peek(c: Char): Boolean = !eof() && text[index] == c

        private fun peekIn(range: CharRange): Boolean = !eof() && text[index] in range
    }
}

class ArchiveValidationException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
