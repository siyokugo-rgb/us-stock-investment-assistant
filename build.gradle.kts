plugins {
    kotlin("jvm") version "2.1.21"
}

group = "usstock"
version = "0.1.0-phase0"

repositories {
    mavenCentral()
}

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
