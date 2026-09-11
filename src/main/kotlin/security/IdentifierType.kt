package security

/**
 * Security 側の外部 identifier 種別。
 *
 * CIK は Issuer / filing-entity 側であり [issuer.IssuerIdentifierType.CIK] のみで扱う。
 * SecurityIdentifier に CIK を載せる経路はない。
 */
enum class IdentifierType {
    TICKER,
    VENDOR_PERMANENT_ID,
}
