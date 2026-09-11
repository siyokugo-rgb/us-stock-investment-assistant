package universe.poc

/**
 * 調査対象 Universe の名称・提供者カタログ（PoC）。
 * 近い index への暗黙置換はしない。曖昧さは notes に残す。
 */
object UniverseSourceCatalog {
    val entries: List<UniverseSourceCatalogEntry> =
        listOf(
            UniverseSourceCatalogEntry(
                universeKey = "SP500_DIVIDEND_ARISTOCRATS",
                displayName = "S&P 500 Dividend Aristocrats",
                officialProvider = "S&P Dow Jones Indices",
                officialIndexIdentifier = "S&P 500 Dividend Aristocrats Index",
                namingAmbiguityNotes = null,
                preferredReconstructionMode = ReconstructionMode.OFFICIAL_MEMBERSHIP_HISTORY,
            ),
            UniverseSourceCatalogEntry(
                universeKey = "DIVIDEND_KINGS",
                displayName = "Dividend Kings",
                officialProvider = "No single official index provider (informal screen)",
                officialIndexIdentifier = null,
                namingAmbiguityNotes =
                    "Not an SPDJI/Nasdaq official index. Lists differ across researchers " +
                        "(e.g. Sure Dividend vs Dividend Growth Investor). " +
                        "Do not treat a blog list as official membership.",
                preferredReconstructionMode = ReconstructionMode.RULE_REBUILD_NOT_OFFICIAL_LIST,
            ),
            UniverseSourceCatalogEntry(
                universeKey = "NASDAQ_DIVIDEND_ACHIEVERS",
                displayName = "Nasdaq Dividend Achievers",
                officialProvider = "Nasdaq Global Indexes",
                officialIndexIdentifier =
                    "NASDAQ US Broad Dividend Achievers Index (DAA) — candidate; " +
                        "Achievers family has multiple variants",
                namingAmbiguityNotes =
                    "Name 'Nasdaq Dividend Achievers' is ambiguous across DAA and related " +
                        "Achievers indices. Do not silently substitute another Achievers index.",
                preferredReconstructionMode = ReconstructionMode.OFFICIAL_MEMBERSHIP_HISTORY,
            ),
            UniverseSourceCatalogEntry(
                universeKey = "SP500_QUALITY",
                displayName = "S&P 500 Quality",
                officialProvider = "S&P Dow Jones Indices",
                officialIndexIdentifier = "S&P 500 Quality Index",
                namingAmbiguityNotes =
                    "Contract name maps to SPDJI 'S&P 500 Quality Index'. Attribution " +
                        "sub-indices exist; do not silently swap them.",
                preferredReconstructionMode = ReconstructionMode.OFFICIAL_MEMBERSHIP_HISTORY,
            ),
        )
}
