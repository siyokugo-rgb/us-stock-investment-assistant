package issuer

import pit.Pit
import java.time.Instant

/**
 * Issuer に紐づく外部 identifier の1版。
 *
 * CIK の validFrom / validTo は今回持たない。
 * 理由: 現行要件は「CIK = Issuer 側 identifier」の責務固定であり、
 * CIK 再割当・移行の実データ契約が未検証のため、推測で履歴期間を導入しない。
 * Issuer↔Security の事実期間は [IssuerSecurityRelation] 側で表現する。
 *
 * knownAt / ingestedAt は valid 期間から生成しない。
 */
data class IssuerIdentifier(
    val issuerId: IssuerId,
    val type: IssuerIdentifierType,
    val value: String,
    val knownAt: Instant,
    val ingestedAt: Instant,
    val source: String,
) {
    init {
        require(source.isNotBlank()) { "source must not be blank" }
        when (type) {
            IssuerIdentifierType.CIK ->
                require(Cik.isCanonical(value)) {
                    "CIK value must be canonical 10-digit zero-padded form, got: '$value'"
                }
        }
        Pit.requireKnownBeforeOrAtIngested(knownAt, ingestedAt)
    }

    fun isAvailableAt(decisionAt: Instant): Boolean = Pit.isAvailableAt(knownAt, decisionAt)

    fun wasHeldBySystemAt(decisionAt: Instant): Boolean = Pit.wasHeldBySystemAt(ingestedAt, decisionAt)

    companion object {
        fun cik(
            issuerId: IssuerId,
            rawCik: String,
            knownAt: Instant,
            ingestedAt: Instant,
            source: String,
        ): IssuerIdentifier =
            IssuerIdentifier(
                issuerId = issuerId,
                type = IssuerIdentifierType.CIK,
                value = Cik.normalize(rawCik),
                knownAt = knownAt,
                ingestedAt = ingestedAt,
                source = source,
            )
    }
}
