package issuer

/**
 * Issuer 側の外部 identifier 種別。
 * 今回は CIK のみ。将来種別追加時も generic framework 化しない。
 */
enum class IssuerIdentifierType {
    /** SEC Central Index Key。Issuer / filing-entity 側。SecurityId ではない。 */
    CIK,
}
