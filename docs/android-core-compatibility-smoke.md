# Android Core Compatibility Smoke

**Baseline (GitHub `main` HEAD at branch cut):** `c33590d7bb4e6e26e112bfc12f2003edc01c9fa5`

## Purpose

Minimal verification that the existing Kotlin/JVM domain core can be:

- referenced from an Android project module
- compiled / DEX'd / packaged into a debug APK
- invoked from Android framework code (Activity)
- exercised with `BigDecimal` / `LocalDate` / `Instant` under the smoke Android conditions

This is **not** Android production implementation.

## What this smoke verifies

- Gradle project dependency: `:android-smoke` → root JVM project (`project(":")`)
- Android compile against root domain types
- D8/DEX + `:android-smoke:assembleDebug` APK packaging of the whole root artifact as consumed by smoke
- Fixture calls (JVM unit test always; on device only if a device/emulator is attached) for:
  - `security.SecurityId`
  - `issuer.IssuerId`
  - `market.DailyPrice` (BigDecimal + LocalDate + Instant + Pit invariants)
  - `dividend.DividendEvent` / `DividendType`
  - `fundamentals.RawFinancialFact` / `FactPeriod` / `NormalizedFinancialConcept`
  - `universe.poc.UniverseMembershipPocQuery.membersAt` (synthetic snapshot fixture)

## What this smoke does **not** verify

- Production UI / Compose / AppCompat / Material / Navigation / DI
- Room / database / providers as production architecture
- Live SEC / Alpha Vantage / any network
- Backtest / Strategy / Corporate Action / 楽天証券
- Product `minSdk` decision
- That PoC HTTP clients (`java.net.http.HttpClient`) work on Android runtime

## Toolchain (chosen without Wrapper change)

| Item | Value | Notes |
| --- | --- | --- |
| Gradle Wrapper | **8.11.1** | unchanged |
| Kotlin | **2.1.21** | root JVM; versions pinned in `settings.gradle.kts` `pluginManagement` |
| AGP | **8.7.2** | Kotlin 2.1 fully supported AGP range ends at 8.7.2 ([Android Kotlin support table](https://developer.android.com/build/kotlin-support)); AGP 8.7 minimum Gradle is 8.9 → Wrapper 8.11.1 OK ([AGP compatibility](https://developer.android.com/build/releases/about-agp)) |
| compileSdk | **35** | AGP 8.7 max API 35; SDK platform `android-35` |
| minSdk | **26** | **smoke-only provisional condition** (java.time native). **Not** a product minSdk decision |
| targetSdk | 35 | smoke only |
| JDK toolchain | 17 | unchanged |

## Root JVM dependency approach

- Existing root project sources stay in place (no `:core` move, no package rename, no source-tree migration of domain packages).
- `settings.gradle.kts` adds `include("android-smoke")` only (+ pluginManagement / Google Maven).
- `:android-smoke` uses `implementation project(':')`.

### Why MainActivity is Java (not Kotlin Android plugin)

Applying `org.jetbrains.kotlin.android` in the same Gradle build as root `org.jetbrains.kotlin.jvm` failed with:

`ClassNotFoundException: com.android.build.gradle.api.BaseVariant` while constructing `KotlinAndroidTarget`.

To avoid large module restructure (`:core` split), smoke uses:

- AGP-only `:android-smoke` with Java `MainActivity`
- Network-free checks in root JVM object `compat.AndroidCoreSmokeLogic` (`@JvmStatic fun run()`)

This is a **smoke packaging choice**, not production Android architecture.

## Module layout

```
android-smoke/
  build.gradle
  src/main/AndroidManifest.xml
  src/main/java/com/usstock/androidsmoke/MainActivity.java

src/main/kotlin/compat/AndroidCoreSmokeLogic.kt   # root JVM, called from Activity
src/test/kotlin/compat/AndroidCoreSmokeLogicTest.kt
```

Framework-only UI: `android.app.Activity` + `TextView`. Compose forbidden.

## JVM-only API audit (`src/main/kotlin`)

| Area | Finding | Impact on smoke verdict |
| --- | --- | --- |
| Pure domain (`security`, `issuer`, `market`, `dividend`, `fundamentals`, `pit`, universe query models) | Uses `BigDecimal`, `java.time`, Kotlin stdlib; no `java.net.http` | Expected to run on Android API 26+ |
| Provider PoC HTTP (`sec.poc`, `market.poc`, `dividend.poc`) | Uses **`java.net.http.HttpClient` / `HttpRequest` / `HttpResponse`** (JVM 11+ API, not Android) | **PoC/provider utility Android-noncompatible**, not “pure domain core fails” |
| Whole-root artifact packaging | Smoke depends on entire root JAR (PoC classes included; not deleted/excluded). DEX of those classes **succeeded**; references to `Ljava/net/http/HttpClient;` are present in APK dex | Structural package OK for smoke; loading PoC HTTP clients on device would be a separate runtime failure |

PoC code was **not** deleted or excluded for smoke convenience.

## Build / device results (Cloud Agent)

| Check | Result |
| --- | --- |
| JVM core regression `./gradlew test --rerun-tasks` | **PASS** — 229 tests passed, 0 failed |
| Android compile | **PASS** |
| DEX / `assembleDebug` | **PASS** |
| APK path (not committed) | `android-smoke/build/outputs/apk/debug/android-smoke-debug.apk` |
| `lintDebug` | **PASS** (0 errors, 2 warnings: `DataExtractionRules`, `MissingApplicationIcon`) |
| `adb devices` | none attached |
| Device runtime | **UNVERIFIED** |
| JVM unit execution of smoke logic | **PASS** (`AndroidCoreSmokeLogicTest`) |

### Later device check (user PC + Xperia; not automated here)

```bash
# local.properties: sdk.dir=<Android SDK>
./gradlew :android-smoke:assembleDebug
adb devices
adb install -r android-smoke/build/outputs/apk/debug/android-smoke-debug.apk
adb shell am start -n com.usstock.androidsmoke/.MainActivity
# Expect on-screen text: ANDROID CORE SMOKE: PASS
```

## Separated verdict

1. JVM core regression: **PASS**
2. Android compile: **PASS**
3. DEX/APK packaging: **PASS**
4. Pure core runtime (device): **UNVERIFIED**
5. Device runtime: **UNVERIFIED**
6. Whole-project Android suitability: **PARTIAL** — domain types package; PoC `java.net.http` remains JVM-oriented; kotlin-android + root kotlin-jvm co-apply blocked without further Gradle isolation

**Overall smoke: PARTIAL**

## Remaining issues

| Severity | Issue |
| --- | --- |
| Medium | Device/emulator runtime of Activity smoke not run in Cloud Agent |
| Medium | Root `kotlin-jvm` + submodule `kotlin-android` classloader conflict (`BaseVariant` CNFE); Java Activity workaround used |
| Medium | Whole-root APK includes PoC HTTP classes referencing `java.net.http` — safe only if those classes are never loaded on Android |
| Low | Product `minSdk` / app architecture still undecided (26 is smoke-only provisional) |
