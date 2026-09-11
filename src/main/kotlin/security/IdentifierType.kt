package security

/**
 * Security 側の外部 identifier 種別。
 *
 * CIK は Issuer / filing-entity 側であり、新規には [issuer.IssuerIdentifierType.CIK] を使う。
 * 本 enum の [CIK] は後方互換のための残置であり、SecurityId 決定に使ってはならない。
 */
enum class IdentifierType {
    TICKER,
    CIK,
    VENDOR_PERMANENT_ID,
}
