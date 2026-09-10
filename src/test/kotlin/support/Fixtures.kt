package support

import dividend.DividendEvent
import dividend.DividendType
import fundamentals.FundamentalSnapshot
import market.DailyPrice
import security.IdentifierType
import security.SecurityId
import security.SecurityIdentifier
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

/**
 * 全テストで共有する固定時刻・固定日付。
 * Instant.now() / LocalDate.now() / Random / 外部API は使わない。
 */
object Fixtures {
    val SEC_A = SecurityId("sec-0001")
    val SEC_B = SecurityId("sec-0002")

    val KNOWN_AT: Instant = Instant.parse("2024-06-10T16:00:00Z")
    val DECISION_BEFORE: Instant = Instant.parse("2024-06-10T15:59:59.999Z")
    val DECISION_EQUAL: Instant = Instant.parse("2024-06-10T16:00:00Z")
    val DECISION_AFTER: Instant = Instant.parse("2024-06-10T16:00:00.001Z")
    val INGESTED_AT: Instant = Instant.parse("2024-06-10T16:10:00Z")
    val INGESTED_LATER: Instant = Instant.parse("2024-06-11T09:00:00Z")

    val AS_OF: LocalDate = LocalDate.of(2024, 6, 10)
    val VALID_FROM: LocalDate = LocalDate.of(2024, 1, 2)
    val VALID_TO: LocalDate = LocalDate.of(2024, 12, 31)

    fun identifier(
        securityId: SecurityId = SEC_A,
        type: IdentifierType = IdentifierType.TICKER,
        value: String = "AAA",
        validFrom: LocalDate = VALID_FROM,
        validTo: LocalDate? = VALID_TO,
        knownAt: Instant = KNOWN_AT,
        ingestedAt: Instant = INGESTED_AT,
        source: String = "fixture-source",
    ): SecurityIdentifier =
        SecurityIdentifier(
            securityId = securityId,
            type = type,
            value = value,
            validFrom = validFrom,
            validTo = validTo,
            knownAt = knownAt,
            ingestedAt = ingestedAt,
            source = source,
        )

    fun price(
        securityId: SecurityId = SEC_A,
        tradingDate: LocalDate = AS_OF,
        open: String = "10.00",
        high: String = "11.00",
        low: String = "9.00",
        close: String = "10.50",
        volume: Long = 1_000L,
        currency: String = "USD",
        knownAt: Instant = KNOWN_AT,
        ingestedAt: Instant = INGESTED_AT,
        source: String = "fixture-source",
    ): DailyPrice =
        DailyPrice(
            securityId = securityId,
            tradingDate = tradingDate,
            open = BigDecimal(open),
            high = BigDecimal(high),
            low = BigDecimal(low),
            close = BigDecimal(close),
            volume = volume,
            currency = currency,
            knownAt = knownAt,
            ingestedAt = ingestedAt,
            source = source,
        )

    fun dividend(
        securityId: SecurityId = SEC_A,
        declarationDate: LocalDate? = LocalDate.of(2024, 5, 1),
        exDate: LocalDate = LocalDate.of(2024, 6, 3),
        recordDate: LocalDate? = LocalDate.of(2024, 6, 4),
        paymentDate: LocalDate? = LocalDate.of(2024, 6, 20),
        amountPerShare: String = "0.25",
        currency: String = "USD",
        dividendType: DividendType = DividendType.REGULAR,
        knownAt: Instant = KNOWN_AT,
        ingestedAt: Instant = INGESTED_AT,
        source: String = "fixture-source",
    ): DividendEvent =
        DividendEvent(
            securityId = securityId,
            declarationDate = declarationDate,
            exDate = exDate,
            recordDate = recordDate,
            paymentDate = paymentDate,
            amountPerShare = BigDecimal(amountPerShare),
            currency = currency,
            dividendType = dividendType,
            knownAt = knownAt,
            ingestedAt = ingestedAt,
            source = source,
        )

    fun fundamental(
        securityId: SecurityId = SEC_A,
        fiscalPeriodEnd: LocalDate = LocalDate.of(2025, 12, 31),
        filedAt: Instant = Instant.parse("2026-02-20T21:00:00Z"),
        knownAt: Instant = Instant.parse("2026-02-20T21:00:00Z"),
        ingestedAt: Instant = Instant.parse("2026-02-21T01:00:00Z"),
        source: String = "fixture-source",
    ): FundamentalSnapshot =
        FundamentalSnapshot(
            securityId = securityId,
            fiscalPeriodEnd = fiscalPeriodEnd,
            filedAt = filedAt,
            knownAt = knownAt,
            ingestedAt = ingestedAt,
            source = source,
        )
}
