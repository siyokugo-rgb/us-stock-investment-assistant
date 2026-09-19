plugins {
    kotlin("jvm")
}

group = "usstock"
version = "0.1.0-phase0"

// Maven repositories: settings.gradle.kts (dependencyResolutionManagement / PREFER_SETTINGS).

dependencies {
    testImplementation(kotlin("test"))
}

kotlin {
    jvmToolchain(17)
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        showStandardStreams = false
    }
}

// Live SEC EDGAR PoC. Requires SEC_EDGAR_USER_AGENT. No mock fallback.
tasks.register<JavaExec>("secEdgarPoc") {
    group = "verification"
    description = "Run live SEC EDGAR submissions metadata PoC (Fail-Closed; requires SEC_EDGAR_USER_AGENT)"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("sec.poc.SecEdgarLivePocKt")
    isIgnoreExitValue = false
}

// Live accession / archive artifact PoC. Requires SEC_EDGAR_USER_AGENT. No mock fallback.
tasks.register<JavaExec>("secEdgarAccessionPoc") {
    group = "verification"
    description =
        "Run live SEC EDGAR accession artifact PoC (index/primary/complete text; Fail-Closed; requires SEC_EDGAR_USER_AGENT)"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("sec.poc.SecEdgarAccessionLivePocKt")
    isIgnoreExitValue = false
}

// Live XBRL CompanyFacts PoC. Requires SEC_EDGAR_USER_AGENT. No mock fallback.
tasks.register<JavaExec>("secCompanyFactsPoc") {
    group = "verification"
    description =
        "Run live SEC XBRL CompanyFacts PoC (Fail-Closed; requires SEC_EDGAR_USER_AGENT; no SecurityId assignment)"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("sec.poc.SecCompanyFactsLivePocKt")
    isIgnoreExitValue = false
}

// Live Alpha Vantage daily price PoC. Uses ALPHAVANTAGE_API_KEY or demo. No mock fallback.
tasks.register<JavaExec>("alphaVantageDailyPoc") {
    group = "verification"
    description =
        "Run live Alpha Vantage TIME_SERIES_DAILY PoC (Fail-Closed; no SecurityId; no knownAt invention)"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("market.poc.AlphaVantageDailyLivePocKt")
    isIgnoreExitValue = false
}

// Live Alpha Vantage dividends PoC. Uses ALPHAVANTAGE_API_KEY or demo. No mock fallback.
tasks.register<JavaExec>("alphaVantageDividendPoc") {
    group = "verification"
    description =
        "Run live Alpha Vantage DIVIDENDS PoC (Fail-Closed; no SecurityId; no knownAt/currency/REGULAR invention)"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("dividend.poc.AlphaVantageDividendLivePocKt")
    isIgnoreExitValue = false
}

// Live OpenFIGI mapping forward archive PoC. Optional OPENFIGI_API_KEY. No mock fallback.
tasks.register<JavaExec>("openFigiForwardArchivePoc") {
    group = "verification"
    description =
        "Run live OpenFIGI /v3/mapping forward self-archive PoC (Fail-Closed; no SecurityId; no knownAt invention)"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("archive.poc.openfigi.OpenFigiLiveArchivePocKt")
    isIgnoreExitValue = true
}

// Live Alpha Vantage TIME_SERIES_DAILY forward archive PoC. Optional ALPHAVANTAGE_API_KEY. No mock fallback.
tasks.register<JavaExec>("alphaVantageDailyForwardArchivePoc") {
    group = "verification"
    description =
        "Run live Alpha Vantage TIME_SERIES_DAILY forward self-archive PoC (Fail-Closed; no SecurityId/knownAt/currency/DailyPrice)"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("archive.poc.alphavantage.AlphaVantageDailyLiveArchivePocKt")
    isIgnoreExitValue = true
}

// Live Massive Custom Bars 1d unadjusted forward archive PoC. Requires MASSIVE_API_KEY else LIVE_UNVERIFIED.
tasks.register<JavaExec>("massiveDailyAggsForwardArchivePoc") {
    group = "verification"
    description =
        "Run live Massive Custom Bars 1d unadjusted forward self-archive PoC (Fail-Closed; no SecurityId/currency/MIC/FIGI/DailyPrice)"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("archive.poc.massive.MassiveDailyAggsLiveArchivePocKt")
    isIgnoreExitValue = true
}

// Live Massive Ticker Overview forward archive PoC. Requires MASSIVE_API_KEY else LIVE_UNVERIFIED.
tasks.register<JavaExec>("massiveTickerOverviewForwardArchivePoc") {
    group = "verification"
    description =
        "Run live Massive Ticker Overview forward self-archive PoC (Fail-Closed; no SecurityId/currency/MIC/FIGI/DailyPrice/join)"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("archive.poc.massive.MassiveTickerOverviewLiveArchivePocKt")
    isIgnoreExitValue = true
}

// Live Massive All Tickers forward archive PoC. Requires MASSIVE_API_KEY else LIVE_UNVERIFIED.
tasks.register<JavaExec>("massiveAllTickersForwardArchivePoc") {
    group = "verification"
    description =
        "Run live Massive All Tickers forward self-archive PoC (Fail-Closed; currency_symbol raw only; no DailyPrice/SecurityId/pagination follow)"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("archive.poc.massive.MassiveAllTickersLiveArchivePocKt")
    isIgnoreExitValue = true
}

// Live Security identity continuity multi-as-of Overview validation. Requires MASSIVE_API_KEY else LIVE_UNVERIFIED.
tasks.register<JavaExec>("massiveSecurityIdentityContinuityLivePoc") {
    group = "verification"
    description =
        "Run live Massive Ticker Overview dated T1/T2 continuity evidence validation " +
            "(Fail-Closed; no SecurityId/SecurityIdentifier/knownAt/DailyPrice; no mock)"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("archive.poc.binding.MassiveSecurityIdentityContinuityLivePocKt")
    isIgnoreExitValue = true
}

// Live Massive Ticker Events schema probe / archive validation. Requires MASSIVE_API_KEY else LIVE_UNVERIFIED.
// Default: 1 request (lookup id XYZ). No mock; no SecurityId/knownAt/continuity.
tasks.register<JavaExec>("massiveTickerEventsForwardArchivePoc") {
    group = "verification"
    description =
        "Run live Massive Ticker Events forward archive schema probe " +
            "(Fail-Closed; default XYZ 1 request; no SecurityId/knownAt/OLD-NEW/continuity; no mock)"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("archive.poc.massive.MassiveTickerEventsLiveArchivePocKt")
    isIgnoreExitValue = true
}

// Live Ticker Events ↔ Overview corroboration validation. Requires MASSIVE_API_KEY else LIVE_UNVERIFIED.
// Fixed free-tier: Overview SQ@2025-01-17 + XYZ@2025-01-22 + Events XYZ (max 3 requests; no retry).
tasks.register<JavaExec>("massiveTickerEventsOverviewLiveCorroborationPoc") {
    group = "verification"
    description =
        "Run live Ticker Events ↔ Overview corroboration validation " +
            "(Fail-Closed; SQ/XYZ Overview + Events XYZ; max 3 requests; no SecurityId/knownAt/DailyPrice; no mock)"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("archive.poc.binding.TickerEventOverviewCorroborationLivePocKt")
    isIgnoreExitValue = true
}
