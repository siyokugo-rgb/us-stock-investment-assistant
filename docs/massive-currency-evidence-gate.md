# Massive Trading Currency Evidence Gate  
## (PRICE + All Tickers；Overview = optional corroboration)

**Date (UTC):** 2026-09-17  
**Repository:** `siyokugo-rgb/us-stock-investment-assistant`  
**Review type:** Gate Review only（Kotlin / TradingCurrencyEvidence / DailyPrice.currency 実装なし）  
**Baseline `origin/main` HEAD:** `7f37df71055d5cee61ef7a7000e293390ee04049`  
**PR #32:** MERGED（`mergedAt=2026-09-17T00:54:25Z`；merge commit = baseline）  
**PR #32 head (ancestor verified):** `03070e06f35dea7d74da6e1da7b8e04ddc35975d`  
**Data Integrity revision:** lowercase auto-normalize 廃止；Overview を Trading Currency 必須 source から外す

| Gate | Verdict |
| --- | --- |
| Raw currency evidence | **PASS** |
| Validated ISO currency evidence | **PARTIAL**（規則固定；未実装） |
| Archive-level Trading Currency | **PARTIAL**（条件固定；temporal UNRESOLVED 維持） |
| Bar-level historical currency | **NO-GO** |
| DailyPrice.currency | **NO-GO** |
| SecurityId | **NO-GO** |
| Venue | **PARTIAL**（本 Gate で変更しない） |
| Forward Research | **CONDITIONAL GO**（維持） |
| Real Backtest | **NO-GO**（維持） |

Prior: [`trading-currency-evidence-gate.md`](trading-currency-evidence-gate.md)（Overview `currency_name` only）。  
本 Gate: All Tickers `currency_symbol` を加えた再評価。**必須コア = PRICE + All Tickers**。Overview は削除せず **OPTIONAL_CORROBORATION**。

---

## 0. Baseline / merge confirmation

| Check | Result |
| --- | --- |
| `git fetch` + `checkout main` + `pull --ff-only` | OK |
| local `main` == `origin/main` | OK (`7f37df71055d5cee61ef7a7000e293390ee04049`) |
| expected baseline HEAD | OK |
| PR #32 state | **MERGED** |
| `03070e06…` is ancestor of `main` | OK |

---

## 1. Development stage

```text
Massive PRICE raw archive
  → Massive All Tickers raw archive (currency_symbol ISO field)
  → 【本レビュー】Trading Currency Evidence Gate
       必須コア: PRICE + All Tickers
       Overview: OPTIONAL_CORROBORATION（Venue/MIC/FIGI/identity 向け再利用）
```

**まだ進まないもの:** TradingCurrencyEvidence 実装、`DailyPrice.currency`、DailyPrice、SecurityId、MIC/Venue resolved、bar-level attribution、past backfill、OpenFIGI auto-join、Backtest、Kotlin。

---

## 2. What exists (facts)

| Artifact | Domain / Source | Currency-related fields | Trading Currency role |
| --- | --- | --- | --- |
| PRICE Custom Bars | `PRICE` / `massive.stocks.aggs_1d.unadjusted` | **none** | 必須（observation 側） |
| All Tickers | `SECURITY_MASTER` / `massive.stocks.all_tickers` | `currency_name` + **`currency_symbol`** | **必須**（currency value authority） |
| Ticker Overview | `SECURITY_MASTER` / `massive.stocks.ticker_overview` | `currency_name` 等 | **OPTIONAL_CORROBORATION** |
| `MassivePriceReferenceBindingEvidence` | derived | may carry Overview `currencyName` | Trading Currency **非必須**（Venue/FIGI 等で再利用） |

明示（All Tickers PoC）:

> OBSERVED raw `currency_symbol` possession ≠ validated ISO 4217 domain value ≠ `DailyPrice.currency`

---

## 3. Official Massive sources（調査日 UTC: 2026-09-17）

公式 docs のみ。非公式ブログ不採用。

| Topic | Source | Recorded meaning |
| --- | --- | --- |
| All Tickers `currency_symbol` | https://massive.com/docs/rest/stocks/tickers/all-tickers | “The **ISO 4217 code** of the currency that this asset is traded with.” |
| All Tickers `currency_name` | same | “traded with” **name** |
| All Tickers `date` / `active` / `ticker` | same | as-of selector；active on date；filterable ticker |
| Overview `currency_name` | https://massive.com/docs/rest/stocks/tickers/ticker-overview | “traded with” name；**no `currency_symbol` in attribute table** |
| Custom Bars | https://massive.com/docs/rest/stocks/aggregates/custom-bars | OHLCV；**no currency attribute** |

---

## 4. Three layers（分離）

| Layer | Meaning | This Gate |
| --- | --- | --- |
| A. provider raw currency evidence | archive の exact field values | PASS 可（`USD` / `usd` 等を **修復せず**保持） |
| B. validated currency evidence **candidate** | raw が canonical ISO 形式かつ membership PASS | **ここまで検討** |
| C. `DailyPrice.currency` | domain adoption | **禁止** |

A PASS ≠ B PASS ≠ C GO。  
**raw 保存と domain adoption 前 validation は別責務。provider deviation を黙って修復しない。**

---

## 5. ISO 4217 validation（Fail-Closed・未実装）

### Layer B required checks（実装時）

1. `currency_symbol` nonblank  
2. raw value そのものが **`^[A-Z]{3}$`**（既に canonical uppercase）  
3. membership in known **ISO 4217 alphabetic** code set  

**自動 uppercase fold / repair 禁止。**

| Raw example | Layer A (raw) | Layer B (validated candidate) |
| --- | --- | --- |
| `USD` | OK | PASS if membership OK → **candidate 可** |
| `usd` | OK（exact raw 保持） | **FAIL** → `CURRENCY_CODE_INVALID` |
| `US Dollar` | OK if archived string | **FAIL** → `CURRENCY_CODE_INVALID` |
| `840` | type/archive 次第 | **FAIL** → `CURRENCY_CODE_INVALID` |

### lowercase normalization 最終判定

**廃止（禁止）。**

- `usd` は Layer A raw evidence のみ  
- Layer B へ昇格させない（修復しない）  
- `currency_name`（`"usd"` / `"US Dollar"`）からの code 推測も禁止  

---

## 6. Identity / ticker chain（必須コア）

| Requirement | Verdict |
| --- | --- |
| PRICE ticker == All Tickers ticker（**case-sensitive exact** Massive namespace） | **必須** |
| Overview ticker match | optional（corroboration 時のみ） |
| ticker match ⇒ SecurityId / historical identity | **否** |

言えるのは **archive-level provider provenance** まで。

---

## 7. Overview 最終位置付け → **OPTIONAL_CORROBORATION**

Trading Currency の必須 source から **外す**。

理由: All Tickers 自身が Massive ticker / `currency_symbol` / raw provenance / eligibility を持つ。Overview を currency value/provenance の必須にすると現目的に過剰。

| Item | Role |
| --- | --- |
| Overview 欠損 | Trading Currency candidate を **拒否しない** |
| Overview あり | corroboration 可（`currency_name`, `primary_exchange`, FIGI, `active`） |
| `MassivePriceReferenceBindingEvidence` | Trading Currency **非必須** |
| 今後の再利用 | Venue/MIC、FIGI consistency、Security identity |

Overview を **削除しない**。

---

## 8. All Tickers 最低条件（currency candidate 用）

| Condition | Required? |
| --- | --- |
| `observationStatus == OBSERVED` | YES |
| `eligibilityBoundaryAt != null` | YES |
| domain/source 期待値 | YES |
| requestKey strict parse | YES |
| **`ticker=` filter 明示** | YES |
| intended ticker **一意** | YES |
| raw integrity PASS | YES |
| validator PASS（active filter 整合含む） | YES |
| `currency_symbol` present | YES |
| ticker-filter無し multi-result 直 join | **NO** |

---

## 9. Eligibility

```text
currencyEvidenceEligibleAt =
  max(
    priceEligibilityBoundaryAt,
    allTickersEligibilityBoundaryAt
  )
```

Overview を使う場合のみ `overviewEligibilityBoundaryAt` を **別補助 evidence** として保持可（必須 max には入れない）。

意味: **evidence usable time（forward）のみ**。  
**ではない:** historical knownAt / request `date` / PRICE bar date / listing validity。

---

## 10. as-of / temporal（維持）

変更しない:

- date omitted ≠ historical validity  
- date supplied ≠ knownAt  
- current currency → old bars backfill **禁止**  
- ticker reuse unresolved  
- bar-level historical currency **NO-GO**  
- `temporalApplicability` **UNRESOLVED**

| Case | All Tickers date | Currency candidate |
| --- | --- | --- |
| A | omitted | Forward archive-level Layer B **条件付き可**；全過去 bar **禁止** |
| B | supplied | as-of state evidence；PRICE bars 自動適用 **禁止**；knownAt 主張 **禁止** |
| E | past date + current ingest | raw/candidate 記録可；ingest ≠ knownAt |

Overview date は corroboration 時の補助 as-of のみ。必須 chain の as-of conflict 判定に Overview 欠損を使わない。

---

## 11. Conflict

| Pattern | Treatment |
| --- | --- |
| same compatible as-of + USD vs CAD（validated symbols） | **CONFLICT**；latest-wins 禁止 |
| different as-of + USD → CAD | **temporal state difference**；latest-wins 禁止 |
| Overview `currency_name` vs All Tickers | 補助 evidence；**name↔code 独自辞書変換禁止**；自動 code rewrite 禁止。必要なら conflict evidence として記録 |

---

## 12. active / inactive / reuse / 推測（維持）

- `active=false` ≠ currency evidence 無効  
- inactive → current live PRICE 自動適用 **禁止**  
- ticker reuse: current currency → old bars backfill **禁止**  
- XNAS/XNYS/locale/FIGI → USD 推測 **禁止**；symbol 欠損は Fail-Closed  

---

## 13. Archive-level Layer B 最低条件（再定義・確定）

1. PRICE OBSERVED  
2. PRICE raw/hash integrity PASS  
3. All Tickers OBSERVED  
4. All Tickers raw/hash integrity PASS  
5. PRICE ticker == All Tickers ticker（case-sensitive exact Massive namespace）  
6. All Tickers request で **ticker filter 明示**  
7. intended ticker result が一意  
8. 両 eligibility あり  
9. `currency_symbol` present  
10. raw `currency_symbol` が canonical **`^[A-Z]{3}$`**  
11. ISO 4217 alphabetic membership PASS  
12. as-of / temporal conflict 無し  
13. currency conflict 無し  

### 必須から外したもの

| Dropped | Why |
| --- | --- |
| Overview OBSERVED / binding CANDIDATE | Trading Currency には過剰；OPTIONAL_CORROBORATION |
| Overview `currency_name` | 値 authority ではない |
| All Tickers `currency_name` | 値 authority = `currency_symbol` |
| lowercase → uppercase auto-repair | Fail-Closed；provider deviation を修復しない |
| `temporalApplicability=RESOLVED` | UNRESOLVED 維持；bar-level 不可 |

---

## 14. TradingCurrencyEvidence model 最終案（実装禁止・今回）

**YES — 次工程で最小実装。** コアは PRICE + All Tickers。

```text
TradingCurrencyEvidence
  // required core
  priceArchiveId
  allTickersArchiveId
  provider                          // Massive
  providerTicker
  rawCurrencySymbol                 // exact provider raw
  canonicalCurrencyCode             // only when Layer B PASS (== raw if ^[A-Z]{3}$ + membership)
  priceEligibilityBoundaryAt
  allTickersEligibilityBoundaryAt
  evidenceEligibleAt                // max(price, allTickers)
  allTickersRequestDate?
  temporalApplicability             // default UNRESOLVED
  status                            // CANDIDATE | INELIGIBLE | CONFLICT
  reason?

  // optional corroboration (Overview)
  overviewArchiveId?
  rawCurrencyName?
  overviewRequestDate?
  overviewEligibilityBoundaryAt?    // auxiliary only; not required in evidenceEligibleAt
```

**禁止（実装時も）:** `DailyPrice.currency`、bar-level attribution、past backfill、SecurityId、lowercase auto-normalize。

---

## 15. Status / reason（必要最小）

**Status:** `CANDIDATE` | `INELIGIBLE` | `CONFLICT`

| Reason | When |
| --- | --- |
| `CURRENCY_MISSING` | `currency_symbol` 欠損 |
| `CURRENCY_CODE_INVALID` | not `^[A-Z]{3}$` or membership FAIL（incl. `usd`） |
| `TICKER_MISMATCH` | PRICE ↔ All Tickers 不一致 |
| `TEMPORAL_UNRESOLVED` | as-of / temporal conflict |
| `CURRENCY_CONFLICT` | same as-of 複数 validated codes |
| `NOT_OBSERVED` | PRICE or All Tickers 非 OBSERVED |
| `ELIGIBILITY_MISSING` | eligibility null |
| `SOURCE_INVALID` | domain/source / ticker filter 無し |

---

## 16. Acceptance Matrix（修正）

| Case | Scenario | Verdict |
| --- | --- | --- |
| 1 | PRICE + All Tickers valid + `currency_symbol=USD` + date omitted | archive-level Layer B **CANDIDATE 可**；DailyPrice **NO** |
| 2 | `currency_symbol` missing | **FAIL**（CURRENCY_MISSING） |
| 3 | `currency_symbol=usd` | Layer A **PASS**；Layer B **FAIL**（CURRENCY_CODE_INVALID；no auto-fold） |
| 4 | `currency_symbol=US Dollar` | **FAIL**（CURRENCY_CODE_INVALID） |
| 5 | PRICE + All Tickers valid / **Overview なし** | archive-level Trading Currency candidate **可** |
| 6 | Overview あり | **corroboration 可**；欠損でも候補拒否しない |
| 7 | Overview `currency_name` conflict | automatic code rewrite **禁止**；必要なら conflict evidence |
| 8 | All Tickers date=2024 + PRICE obs 2026 | as-of evidence 可；bar auto-apply **禁止** |
| 9 | date omitted | Forward archive-level candidate **条件付き可**；past bars **禁止** |
| 10 | current currency + old PRICE bars | past backfill **禁止** |
| 11 | `active=false` + as-of currency | evidence 保持可；current PRICE 自動適用 **禁止** |
| 12 | same as-of USD/CAD | **CONFLICT** |
| 13 | different as-of USD→CAD | temporal state difference；latest-wins **禁止** |
| 14 | `primary_exchange=XNAS` + symbol missing | USD 推測 **禁止** |

---

## 17. Mandatory answers（改訂）

| # | Question | Answer |
| --- | --- | --- |
| A | All Tickers `currency_symbol` 採用可能か | **YES as Layer B**（raw が `^[A-Z]{3}$` + membership）。DailyPrice ではない |
| B | ISO validation | raw canonical `^[A-Z]{3}$` + ISO 4217 alphabetic membership；**no auto-uppercase** |
| C | lowercase `usd` normalize | **禁止**。raw only → `CURRENCY_CODE_INVALID` |
| D | Overview 必須か | **NO** — OPTIONAL_CORROBORATION |
| E | All Tickers `currency_name` 必須か | **NO** |
| F | 最低 chain | **PRICE + All Tickers**（§13） |
| G–I | date / bar-level | §10；bar-level **NO-GO** |
| J | TradingCurrencyEvidence 次実装か | **YES（A）** — コア PRICE+All Tickers；Overview optional；DailyPrice 開けない |

---

## 18. Separated verdicts（維持）

| Gate | Verdict |
| --- | --- |
| Raw currency evidence | **PASS** |
| Validated ISO currency evidence | **PARTIAL** |
| Archive-level Trading Currency | **PARTIAL** |
| Bar-level historical currency | **NO-GO** |
| DailyPrice.currency | **NO-GO** |
| SecurityId | **NO-GO** |
| Venue | **PARTIAL** |
| Forward Research | **CONDITIONAL GO** |
| Real Backtest | **NO-GO** |

---

## 19. Residual blockers

| Item | Severity |
| --- | --- |
| historical knownAt / CA PIT / Universe | **Critical** |
| `DailyPrice.currency` adoption | **Critical** |
| Bar-level currency / ticker reuse history | **Critical** |
| ISO 4217 membership table + `^[A-Z]{3}$` validation 実装 | **High**（次 A に内包） |
| `temporalApplicability` still UNRESOLVED | **High** |
| MIC / Venue / FIGI（Overview corroboration 経路） | **High** |
| Complete inactive/delisted universe / pagination | **High** |

---

## 20. Next（1 つのみ）

| Option | Select? |
| --- | --- |
| **A. TradingCurrencyEvidence 最小実装** | **YES** |
| B. ISO 4217 validation support 単独 | A に内包 |
| C–E | 別軸 |

**選定理由:** 必須コア = PRICE + All Tickers；`currency_symbol` 公式 ISO；Fail-Closed（no lowercase repair）；Overview optional。次は Layer B CANDIDATE 派生。`DailyPrice.currency` は作らない。

---

## 21. Related docs

- [`trading-currency-evidence-gate.md`](trading-currency-evidence-gate.md)
- [`massive-all-tickers-forward-archive-poc.md`](massive-all-tickers-forward-archive-poc.md)
- [`massive-price-overview-binding-poc.md`](massive-price-overview-binding-poc.md)
- [`massive-price-forward-archive-poc.md`](massive-price-forward-archive-poc.md)
- [`massive-ticker-overview-forward-archive-poc.md`](massive-ticker-overview-forward-archive-poc.md)
- [`forward-self-archive-design.md`](forward-self-archive-design.md)
- [`security-master-acceptance-criteria.md`](security-master-acceptance-criteria.md)

---

## 22. Merge advice

- Draft PR としてレビュー可  
- **main 自動 merge しない**  
- 本文書は `DailyPrice.currency` / SecurityId / Real Backtest / bar-level / lowercase repair の許可書ではない  
- Archive-level PARTIAL ≠ Trading Currency SOLVED ≠ Layer C  
