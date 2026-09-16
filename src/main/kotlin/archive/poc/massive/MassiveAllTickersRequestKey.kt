package archive.poc.massive

/**
 * Strict parse of Massive All Tickers archive [requestKey].
 *
 * Canonical form:
 * `GET|/v3/reference/tickers|market=stocks[|ticker=…][|active=…][|date=…]|limit=N[|sort=…][|order=…]`
 *
 * Fail-Closed on any deviation. API key must never appear.
 */
object MassiveAllTickersRequestKey {
    data class Parsed(
        val ticker: String?,
        val active: Boolean?,
        val date: String?,
        val limit: Int,
        val sort: String?,
        val order: String?,
    )

    fun parseOrThrow(requestKey: String): Parsed {
        val parts = requestKey.split('|')
        require(parts.size >= 3) {
            "malformed Massive all-tickers requestKey: too few segments"
        }
        require(parts[0] == "GET") {
            "malformed Massive all-tickers requestKey: method must be GET"
        }
        require(parts[1] == MassiveAllTickersArchiveClient.PATH) {
            "malformed Massive all-tickers requestKey: path must be " +
                MassiveAllTickersArchiveClient.PATH
        }
        require(parts[2] == "market=${MassiveAllTickersArchiveClient.MARKET_STOCKS}") {
            "malformed Massive all-tickers requestKey: market must be stocks"
        }

        var ticker: String? = null
        var active: Boolean? = null
        var date: String? = null
        var limit: Int? = null
        var sort: String? = null
        var order: String? = null
        var seenLimit = false

        for (i in 3 until parts.size) {
            val seg = parts[i]
            when {
                seg.startsWith("ticker=") -> {
                    require(ticker == null) {
                        "malformed Massive all-tickers requestKey: duplicate ticker"
                    }
                    require(!seenLimit && sort == null && order == null) {
                        "malformed Massive all-tickers requestKey: ticker out of order"
                    }
                    val raw = seg.removePrefix("ticker=")
                    require(raw.isNotBlank()) {
                        "malformed Massive all-tickers requestKey: blank ticker"
                    }
                    require(!raw.contains('|') && raw == raw.trim()) {
                        "malformed Massive all-tickers requestKey: illegal ticker"
                    }
                    ticker = raw
                }
                seg.startsWith("active=") -> {
                    require(active == null) {
                        "malformed Massive all-tickers requestKey: duplicate active"
                    }
                    require(!seenLimit && sort == null && order == null) {
                        "malformed Massive all-tickers requestKey: active out of order"
                    }
                    val raw = seg.removePrefix("active=")
                    active =
                        when (raw) {
                            "true" -> true
                            "false" -> false
                            else ->
                                throw IllegalArgumentException(
                                    "malformed Massive all-tickers requestKey: illegal active",
                                )
                        }
                }
                seg.startsWith("date=") -> {
                    require(date == null) {
                        "malformed Massive all-tickers requestKey: duplicate date"
                    }
                    require(!seenLimit && sort == null && order == null) {
                        "malformed Massive all-tickers requestKey: date out of order"
                    }
                    val raw = seg.removePrefix("date=")
                    require(DATE.matches(raw)) {
                        "malformed Massive all-tickers requestKey: illegal date"
                    }
                    date = raw
                }
                seg.startsWith("limit=") -> {
                    require(!seenLimit) {
                        "malformed Massive all-tickers requestKey: duplicate limit"
                    }
                    require(sort == null && order == null) {
                        "malformed Massive all-tickers requestKey: limit out of order"
                    }
                    val raw = seg.removePrefix("limit=")
                    val parsedLimit =
                        raw.toIntOrNull()
                            ?: throw IllegalArgumentException(
                                "malformed Massive all-tickers requestKey: limit not int",
                            )
                    require(parsedLimit > 0) {
                        "malformed Massive all-tickers requestKey: limit must be positive"
                    }
                    require(parsedLimit <= MassiveAllTickersArchiveClient.MAX_LIMIT) {
                        "malformed Massive all-tickers requestKey: limit exceeds max"
                    }
                    limit = parsedLimit
                    seenLimit = true
                }
                seg.startsWith("sort=") -> {
                    require(seenLimit) {
                        "malformed Massive all-tickers requestKey: sort before limit"
                    }
                    require(sort == null) {
                        "malformed Massive all-tickers requestKey: duplicate sort"
                    }
                    require(order == null) {
                        "malformed Massive all-tickers requestKey: sort after order"
                    }
                    val raw = seg.removePrefix("sort=")
                    require(raw.isNotBlank()) {
                        "malformed Massive all-tickers requestKey: blank sort"
                    }
                    sort = raw
                }
                seg.startsWith("order=") -> {
                    require(seenLimit) {
                        "malformed Massive all-tickers requestKey: order before limit"
                    }
                    require(order == null) {
                        "malformed Massive all-tickers requestKey: duplicate order"
                    }
                    val raw = seg.removePrefix("order=")
                    require(raw.isNotBlank()) {
                        "malformed Massive all-tickers requestKey: blank order"
                    }
                    order = raw
                }
                else ->
                    throw IllegalArgumentException(
                        "malformed Massive all-tickers requestKey: unknown segment=$seg",
                    )
            }
        }

        require(seenLimit && limit != null) {
            "malformed Massive all-tickers requestKey: missing limit"
        }

        val canonical =
            MassiveAllTickersArchiveClient.requestKey(
                ticker = ticker,
                active = active,
                date = date,
                limit = limit!!,
                sort = sort,
                order = order,
            )
        require(canonical == requestKey) {
            "malformed Massive all-tickers requestKey: not canonical " +
                "(got=$requestKey expected=$canonical)"
        }
        return Parsed(
            ticker = ticker,
            active = active,
            date = date,
            limit = limit,
            sort = sort,
            order = order,
        )
    }

    private val DATE = Regex("^\\d{4}-\\d{2}-\\d{2}$")
}
