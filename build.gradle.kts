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
