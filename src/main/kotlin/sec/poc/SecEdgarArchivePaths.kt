package sec.poc

/**
 * SEC 公式 Accessing EDGAR Data に記載の archive パス規則。
 *
 * Post-EDGAR 7.0:
 * `/Archives/edgar/data/{issuerCikNoLeadingZeros}/{accessionNoDashes}/...`
 *
 * パス上の CIK は **subject issuer CIK**（submissions の会社 CIK）。
 * accession 先頭 10 桁（提出者 CIK）とは一致しない場合がある。
 */
object SecEdgarArchivePaths {
    const val DEFAULT_WWW_BASE = "https://www.sec.gov"

    fun accessionDirectory(
        issuerCik: SecCik,
        accession: SecAccessionNumber,
        wwwBase: String = DEFAULT_WWW_BASE,
    ): String {
        val base = wwwBase.trimEnd('/')
        return "$base/Archives/edgar/data/${issuerCik.forArchivesPath()}/${accession.compactNoDashes}"
    }

    fun filingIndexHtml(
        issuerCik: SecCik,
        accession: SecAccessionNumber,
        wwwBase: String = DEFAULT_WWW_BASE,
    ): String = "${accessionDirectory(issuerCik, accession, wwwBase)}/${accession.value}-index.htm"

    fun primaryDocument(
        issuerCik: SecCik,
        accession: SecAccessionNumber,
        primaryDocumentFileName: String,
        wwwBase: String = DEFAULT_WWW_BASE,
    ): String {
        val file = primaryDocumentFileName.trim().trimStart('/')
        if (file.isEmpty()) {
            throw SecEdgarPocException("primaryDocument file name must not be blank")
        }
        return "${accessionDirectory(issuerCik, accession, wwwBase)}/$file"
    }

    fun completeSubmissionText(
        issuerCik: SecCik,
        accession: SecAccessionNumber,
        wwwBase: String = DEFAULT_WWW_BASE,
    ): String = "${accessionDirectory(issuerCik, accession, wwwBase)}/${accession.value}.txt"
}
