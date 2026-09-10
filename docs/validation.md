# 検証記録

記録日（実行環境の日付）: 2026-09-10  
対象ブランチ: `cursor/pit-safe-kotlin-core-bccf`  
起点: `ed86d18f6005567b0dbf77d4254b0523aa47f83e`（GitHub `main` Initial commit）

未実行の項目を PASS と書かない。本記録は **core のビルド・単体テスト・コード監査** のみ。戦略・実データ・バックテストの正しさは未検証である。

---

## 実行環境

| 項目 | 値 | 根拠 |
| --- | --- | --- |
| JDK（ビルド/テスト） | OpenJDK 17.0.20（Ubuntu, `JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64`） | `java -version` および Gradle toolchain 検出 |
| JVM toolchain | 17 | `build.gradle.kts` の `jvmToolchain(17)`。コンパイルログ `jdkHome=/usr/lib/jvm/java-17-openjdk-amd64` |
| Gradle | 8.11.1 | Wrapper `gradle-wrapper.properties` |
| Kotlin | 2.1.21 | `kotlin("jvm") version "2.1.21"`、実行時 `kotlin-stdlib:2.1.21` |
| 互換性 | Kotlin 2.1.20–2.1.21 は Gradle 7.6.3–8.12.1 が公式サポート範囲 | Kotlin 公式 Gradle 互換表 |

テストランナーは `kotlin-test` / JUnit Platform。本番コードに外部 library は追加していない。

---

## build

コマンド:

```bash
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
./gradlew --no-daemon build
```

結果: **SUCCESS**（exit code 0）

`build` は `test` を含む。失敗タスクなし。

補足: Gradle タスク `:checkKotlinGradlePluginConfigurationErrors` は KGP の no-op として SKIPPED になることがある。これは **テストの skipped ではない**。

---

## test

コマンド:

```bash
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
./gradlew --no-daemon test
```

結果: **BUILD SUCCESSFUL**

`build/test-results/test/TEST-*.xml` の集計:

| クラス | tests | failures | errors | skipped |
| --- | ---: | ---: | ---: | ---: |
| `dividend.DividendEventTest` | 6 | 0 | 0 | 0 |
| `fundamentals.FundamentalSnapshotTest` | 5 | 0 | 0 | 0 |
| `market.DailyPriceTest` | 11 | 0 | 0 | 0 |
| `pit.PitAvailabilityTest` | 8 | 0 | 0 | 0 |
| `security.SecurityIdentifierTest` | 11 | 0 | 0 | 0 |
| `support.DeterministicFixturesTest` | 1 | 0 | 0 | 0 |
| **合計** | **42** | **0** | **0** | **0** |

必須ケースとの対応:

1. `decisionAt < knownAt` → unavailable — `PitAvailabilityTest.decisionAtBeforeKnownAtIsUnavailable`
2. `decisionAt == knownAt` → available — `decisionAtEqualToKnownAtIsAvailable`
3. `decisionAt > knownAt` → available — `decisionAtAfterKnownAtIsAvailable`
4. validFrom 前 → 利用不可 — `SecurityIdentifierTest.unavailableBeforeValidFrom`
5. validTo 後 → 利用不可 — `unavailableOnAndAfterExclusiveValidTo`
6. knownAt 前 → 利用不可 — `unavailableBeforeKnownAtEvenIfDateIsInValidityWindow`
7. Ticker 変更後も同一 SecurityId — `tickerChangeKeepsSameSecurityId`
8. 同じ Ticker でも異なる SecurityId なら別 — `sameTickerStringWithDifferentSecurityIdsAreDistinctSecurities`
9. Ticker recycle を文字列だけで誤結合しない — `tickerRecycleIsNotMergedByStringAlone`
10. 任意日付 null を保持 — `DividendEventTest.optionalDatesMayBeNull`
11. 負の配当額を拒否 — `negativeAmountIsRejected`
12. 不正 OHLC を拒否 — `DailyPriceTest` の negative / high<low / open・close 範囲外
13. 異なる SecurityId を誤結合しない — price / dividend / fundamental / PIT query
14. 現在時刻・乱数・外部 API 非依存 — fixture は `Instant.parse` / `LocalDate.of` のみ。`src` に `Instant.now` / `LocalDate.now` / `Random` / HTTP クライアントなし

---

## PIT 監査（コード + テスト）

実施内容: モデル実装の読取り、および上記テスト。実データ・ベンダー配信遅延は対象外。

確認できたこと:

- 知識PIT `isAvailableAt` は `knownAt` のみを使い、`decisionAt.isBefore(knownAt)` なら false。等号は available。
- 保有PIT `wasHeldBySystemAt` は別メソッド。`isAvailableAt` は `ingestedAt` を見ない。
- `LocalDate` を `atStartOfDay` 等で `Instant` に変換するコードはない。
- Identifier の `isValidOn(asOfDate)` と `isAvailableAt(decisionAt)` は分離。
- `PitQuery` と `SecurityIdentifierIndex` は候補集合を返し、最新版選択・source 自動解決・Ticker 統合をしない。
- `FundamentalSnapshot` は `fiscalPeriodEnd` ではなく `knownAt` で可用性を判定する。2026-01-15 decision vs 2025-12-31 期末の例をテストした。
- 配当の可用性も `exDate` ではなく `knownAt`。

未監査（データが無いため）:

- 実ベンダーの `knownAt` が「根拠付きで利用可能になった時刻」になっているか
- 取引所カレンダー、訂正配信、遅延

---

## データ意味監査（コード + 仕様）

確認できたこと:

- `SecurityId` は Ticker から導出しない opaque 文字列。
- `SecurityIdentifier` は type/value の履歴であり、valid 期間と knownAt が別。
- `DailyPrice` に adjusted close フィールドはない。仕様に「執行価格として使わない」と明記。
- 金額は `BigDecimal`。OHLC 比較は `compareTo`（scale 差で誤拒否しない）。
- `volume` は株数として `Long`（金額ではない）。
- 負価格・負出来高・high<low・open/close のレンジ外れをコンストラクタで拒否。
- `DividendEvent` は `declarationDate` / `recordDate` / `paymentDate` を null 可。`exDate` は必須。負額拒否。
- `FundamentalSnapshot` に財務数値 Map はない。`filedAt <= knownAt <= ingestedAt` を強制。
- 本番経路に mock / synthetic 株価 fallback はない（データ取得経路自体が無い）。

未監査:

- 実 OHLC が売買可能な価格か
- 配当の currency / 税 / ADR 調整
- CIK と Ticker の実世界対応

---

## README / spec / implementation 整合

一致:

- Phase -1 + Phase 0、自動売買ではない、楽天第一候補、moomoo 除外、資金 10,000円
- QDR 未検証で未実装
- PIT 2種類、Fail-Closed、Survivorship Bias Critical
- 禁止リスト（Android, HTTP, 戦略, mock fallback 等）どおり未実装
- モデル名と必須フィールド

意図的な差・仮決め（不一致というより未指定の補完）:

- `validTo` を exclusive とした。元要件は境界を指定していない。spec に High issue として残した。
- 全モデルに `knownAt <= ingestedAt` を入れた。要件文が明示していたのは fundamentals の `filedAt <= knownAt <= ingestedAt`。価格・配当・識別子へ同じ順序を適用した。
- README はフィールド一覧を spec に委譲している。

テスト PASS は QDR や実データ API の正しさを意味しない。
