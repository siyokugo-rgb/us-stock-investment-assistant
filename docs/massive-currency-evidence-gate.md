# Massive Trading Currency Evidence Gate  
## (PRICE + Ticker Overview + All Tickers)

**Date (UTC):** 2026-09-17  
**Repository:** `siyokugo-rgb/us-stock-investment-assistant`  
**Review type:** Gate Review only（Kotlin / TradingCurrencyEvidence / DailyPrice.currency 実装なし）  
**Baseline `origin/main` HEAD:** `7f37df71055d5cee61ef7a7000e293390ee04049`  
**PR #32:** MERGED（`mergedAt=2026-09-17T00:54:25Z`；merge commit = baseline）  
**PR #32 head (ancestor verified):** `03070e06f35dea7d74da6e1da7b8e04ddc35975d`

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

Prior single-source Gate: [`trading-currency-evidence-gate.md`](trading-currency-evidence-gate.md)（Overview `currency_name` only → PARTIAL）。  
本 Gate は All Tickers `currency_symbol` を加えた **3-source** 再評価。

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
  → Massive Ticker Overview raw archive
  → Same-vendor PRICE↔Overview binding (CANDIDATE; temporal UNRESOLVED)
  → Massive All Tickers raw archive (currency_symbol ISO field)
  → 【本レビュー】3-source Trading Currency Evidence Gate
```

**まだ進まないもの:** TradingCurrencyEvidence 実装、`DailyPrice.currency`、DailyPrice、SecurityId、MIC/Venue resolved、bar-level attribution、past backfill、OpenFIGI auto-join、Backtest、Kotlin。

---

## 2. What exists (facts)

| Artifact | Domain / Source | Currency-related fields |
| --- | --- | --- |
| PRICE Custom Bars | `PRICE` / `massive.stocks.aggs_1d.unadjusted` | **none** in response schema |
| Ticker Overview | `SECURITY_MASTER` / `massive.stocks.ticker_overview` | `currency_name`（no `currency_symbol`） |
| All Tickers | `SECURITY_MASTER` / `massive.stocks.all_tickers` | `currency_name` + **`currency_symbol`** |
| `MassivePriceReferenceBindingEvidence` | derived | may carry Overview `currencyName` raw；`referenceTemporalApplicability=UNRESOLVED` even on CANDIDATE |

明示（All Tickers PoC）:

> OBSERVED raw `currency_symbol` possession ≠ validated ISO 4217 domain value ≠ `DailyPrice.currency`

---

## 3. Official Massive sources（調査日 UTC: 2026-09-17）

公式 docs のみ。非公式ブログ不採用。

| Topic | Source | Recorded meaning |
| --- | --- | --- |
| Overview `currency_name` | https://massive.com/docs/rest/stocks/tickers/ticker-overview | “The name of the currency that this asset is **traded with**.” Sample: `"usd"` |
| Overview `date` | same | as-of selector for ticker info available on that date；≠ knownAt |
| Overview `ticker` | same | case-sensitive path param |
| Overview `primary_exchange` | same | ISO code of primary listing exchange |
| All Tickers `currency_name` | https://massive.com/docs/rest/stocks/tickers/all-tickers | same “traded with” wording |
| All Tickers `currency_symbol` | same | “The **ISO 4217 code** of the currency that this asset is traded with.” |
| All Tickers `date` | same | point in time for tickers available on that date；default = most recent |
| All Tickers `active` | same | actively traded on queried date；false = delisted |
| Custom Bars | https://massive.com/docs/rest/stocks/aggregates/custom-bars | OHLCV；**no currency attribute** |

**契約再確認（本 Gate）:**  
`currency_name` = traded-with **name**；`currency_symbol` = traded-with **ISO 4217 code**。  
Overview に `currency_symbol` は公式 attribute table 上 **無い**。

---

## 4. Three layers（分離）

| Layer | Meaning | This Gate |
| --- | --- | --- |
| A. provider raw currency evidence | archive に存在する exact field values | 評価・利用可 |
| B. validated currency evidence **candidate** | ISO 規則を通した archive-level trading currency candidate | **ここまで検討** |
| C. `DailyPrice.currency` | domain adoption | **禁止** |

A PASS ≠ B PASS ≠ C GO。

---

## 5. ISO 4217 validation（文書方針・未実装）

All Tickers `currency_symbol` を Layer B candidate にするには、公式「ISO 4217」定義 **に加え** 値検証が必要。

### Required checks（実装時）

1. nonblank string  
2. exactly **3 ASCII alphabetic** characters (`^[A-Za-z]{3}$`)  
3. **ASCII uppercase canonicalization** → `USD` 形式  
4. membership in known **ISO 4217 alphabetic** code set  

| Raw example | Layer A (raw) | Layer B (validated candidate) |
| --- | --- | --- |
| `USD` | OK | PASS if membership OK |
| `usd` | OK（raw 保持済み） | uppercase fold **後** membership → candidate 可 |
| `US Dollar` | type OK if archived string | **FAIL**（not 3-letter alphabetic code） |
| `840` | reject at archive type or B FAIL | **FAIL**（numeric code ≠ alphabetic） |

### lowercase normalize 判定

**条件付き許可（currency_symbol のみ）:**

- 根拠: 公式 field = ISO 4217 **alphabetic code**；ISO 4217 alphabetic codes の canonical form は uppercase  
- 許可操作: ASCII `A–Z` への case-fold **のみ**（locale-sensitive fold 禁止）  
- 禁止: `currency_name`（`"usd"` / `"US Dollar"`）からの code 推測・無根拠 title-case・独自辞書  

`usd` → raw evidence として既に保存可。  
Layer B では `USD` へ fold + membership 成功時のみ validated candidate。  
fold 成功 ≠ `DailyPrice.currency` 設定。

---

## 6. Identity / ticker chain

| Requirement | Verdict |
| --- | --- |
| PRICE / Overview / All Tickers ticker = **case-sensitive exact match** in Massive namespace | **必須**（archive-level provenance） |
| ticker match ⇒ SecurityId | **否** |
| ticker match ⇒ historical identity / reuse-proof | **否** |

言えるのは **archive-level provider provenance** まで。

---

## 7. Existing binding + All Tickers（3者関係）

`MassivePriceReferenceBindingEvidence` **CANDIDATE** を Trading Currency candidate の **必要条件**とする。

理由: PRICE に currency が無く、Overview が same-vendor reference provenance の既存契約だから。

```text
PRICE OBSERVED
  + Overview OBSERVED
  → MassivePriceReferenceBindingEvidence CANDIDATE
  + All Tickers OBSERVED (ticker-filtered)
  → Trading Currency Layer B candidate（条件満たす場合）
```

All Tickers は binding を置換しない。**追加 evidence source**。  
binding の `referenceTemporalApplicability=UNRESOLVED` は本 Gate でも **SOLVED にしない**。

---

## 8. All Tickers 最低条件（currency candidate 用）

| Condition | Required? |
| --- | --- |
| `observationStatus == OBSERVED` | YES |
| `eligibilityBoundaryAt != null` | YES |
| domain=`SECURITY_MASTER`, source=`massive.stocks.all_tickers` | YES |
| requestKey strict parse | YES |
| **`ticker=` filter 明示** | YES |
| intended ticker **exact one**（response も一致；multi-ticker page 直 join 禁止） | YES |
| raw integrity PASS | YES |
| archive validator PASS（incl. active filter 整合） | YES |
| `currency_symbol` present + nonblank | YES（Layer B） |
| ticker-filter無し multi-result | **NO** — 個別 PRICE へ直接 join 不可 |

安全側: **explicit ticker-filtered single-security observation** を要求。

---

## 9. Eligibility

```text
currencyEvidenceEligibleAt =
  max(
    priceEligibilityBoundaryAt,
    overviewEligibilityBoundaryAt,
    allTickersEligibilityBoundaryAt
  )
```

意味: **evidence usable time（forward）のみ**。

**ではない:** historical knownAt / Overview `date` / All Tickers `date` / PRICE bar date / listing validity。

---

## 10. as-of / date 整合

Overview `date` と All Tickers `date` はどちらも provider as-of selector。

| Case | Dates | Currency candidate 扱い |
| --- | --- | --- |
| A | both omitted | Forward archive-level Layer B **条件付き可**（§11）；historical bars 全適用 **禁止** |
| B | Overview date == All Tickers date | compatible as-of；provider as-of state evidence。auto-apply to PRICE bars **禁止** |
| C | Overview date ≠ All Tickers date | **TEMPORAL_UNRESOLVED** / 非 candidate（as-of conflict） |
| D | only one date supplied | **TEMPORAL_UNRESOLVED**（片側 latest vs 片側 as-of を同一 state とみなさない） |
| E | past date + current ingest | as-of raw evidence 保持可；ingest ≠ knownAt；2026 PRICE へ自動適用 **禁止** |

`date` 一致 ≠ historical knownAt。

---

## 11. date omitted（Forward）

both omitted（Case A）:

- `currencyEvidenceEligibleAt` **以降**の forward research における **archive-level** Layer B candidate → **条件付き可**  
- date omitted = current/latest reference ≠ 全過去 bar 有効 → **禁止**  
- `temporalApplicability` は当面 **UNRESOLVED** のまま（bar-level は開けない）

---

## 12. date supplied

例: Overview `date=2024-06-01`、All Tickers `date=2024-06-01`、ingest 2026、`currency_symbol=USD`

- provider as-of state の raw / Layer B candidate 記録は可（compatible as-of）  
- 「2024 当時 known だった」とは言わない  
- 2026 PRICE observation / bars へ自動適用しない  

---

## 13. `currency_name` ↔ `currency_symbol` 整合

### Overview `currency_name` 必須性 → **C（必須ではない）**

| Option | Decision |
| --- | --- |
| A. All Tickers `currency_symbol` only | 値 authority としてはこれ |
| B. Overview name 一致必須 | **採用しない**（name↔code 辞書が無い；過剰二重） |
| **C. Overview = provenance binding；currency value authority = All Tickers `currency_symbol`** | **採用** |

Overview OBSERVED + binding CANDIDATE は **provenance 必須**。  
Overview `currency_name` は Layer B の **値必須ではない**（あれば raw 保持）。

### All Tickers `currency_name` 必須性 → **NO**

値 authority は `currency_symbol`。  
name 欠損でも symbol が ISO PASS なら Layer B 可。

### 矛盾時

- **独自名前↔code 辞書で推測しない**  
- 同一 compatible as-of で **validated `currency_symbol` が複数（USD vs CAD）** → **CONFLICT**  
- Overview `currency_name` と All Tickers `currency_name` が両方非blankかつ case-fold 不等 → **CONFLICT**（同一 “traded with” name 意味）  
- Overview name vs All Tickers symbol の意味変換比較は **しない**（辞書無し）

---

## 14. active / inactive

| Rule | Verdict |
| --- | --- |
| `active=false` ⇒ currency evidence 無効 | **否**（historical/as-of evidence は存在し得る） |
| inactive/delisted → current live PRICE へ自動適用 | **禁止** |
| explicit `active` filter ↔ response 整合 | All Tickers archive 既定の Fail-Closed を維持 |

---

## 15. ticker reuse / change

- current All Tickers currency → old PRICE bars backfill **禁止**  
- currency candidate 成立 ≠ historical ticker continuity 証明  
- history 無しでは recycle-proof 不可  

---

## 16. 推測禁止

欠損時 Fail-Closed（`CURRENCY_MISSING` / `CURRENCY_CODE_INVALID`）。

禁止: XNAS/XNYS/US locale/FIGI → USD。

---

## 17. Conflict vs temporal state vs revision

| Pattern | Treatment |
| --- | --- |
| same compatible as-of + conflicting validated symbols | **CONFLICT**；latest-wins 禁止 |
| different request dates + different symbols | **temporal state difference**；latest-wins 禁止 |
| same requestKey + different raw hash | **revisionCandidate**（≠ correction；≠ currency CONFLICT 自動） |

---

## 18. Archive-level Layer B 最低条件（確定）

1. PRICE OBSERVED + integrity  
2. `MassivePriceReferenceBindingEvidence` = **CANDIDATE**  
3. All Tickers OBSERVED + integrity  
4. same exact Massive ticker（PRICE / Overview / All Tickers）  
5. All Tickers **ticker filter 明示** + single intended ticker  
6. all three eligibility ≠ null  
7. `currency_symbol` present  
8. ISO 4217 Layer B validation PASS（§5）  
9. as-of compatible（§10 Case A or B；C/D は不可）  
10. no currency CONFLICT  

### 削ったもの / 理由

| Dropped as mandatory | Why |
| --- | --- |
| Overview `currency_name` nonblank | value authority = All Tickers symbol（§13 C） |
| All Tickers `currency_name` nonblank | 同上 |
| `temporalApplicability=RESOLVED` | 現状 UNRESOLVED；archive-level Forward candidate は UNRESOLVED のまま可、bar-level は不可 |

---

## 19. TradingCurrencyEvidence class 必要性

**YES — 次工程で最小実装候補。**  
3 archive id と eligibility / as-of / raw+canonical / status を結ぶ派生 evidence が無いと、条件が散在する。

### 文書上の最小 model 案（実装禁止・今回）

```text
TradingCurrencyEvidence
  priceArchiveId
  overviewArchiveId
  allTickersArchiveId
  provider                    // Massive
  providerTicker
  rawCurrencyName?            // Overview and/or All Tickers name（optional）
  rawCurrencySymbol           // All Tickers exact raw
  canonicalCurrencyCode?      // ISO uppercase alphabetic if Layer B PASS
  priceEligibilityBoundaryAt
  overviewEligibilityBoundaryAt
  allTickersEligibilityBoundaryAt
  evidenceEligibleAt          // max of three
  overviewRequestDate?
  allTickersRequestDate?
  temporalApplicability       // default UNRESOLVED
  status                      // CANDIDATE | INELIGIBLE | CONFLICT
  reason?                     // see §20
```

**禁止（実装時も）:** `DailyPrice.currency` 設定、bar-level attribution、past backfill、SecurityId。

---

## 20. Status / reason（必要最小）

**Status:** `CANDIDATE` | `INELIGIBLE` | `CONFLICT`

**Reason（必要なものだけ）:**

| Reason | When |
| --- | --- |
| `CURRENCY_MISSING` | `currency_symbol` 欠損 |
| `CURRENCY_CODE_INVALID` | ISO 規則 FAIL |
| `TICKER_MISMATCH` | chain 不一致 |
| `TEMPORAL_UNRESOLVED` | as-of incompatible / Case C–D |
| `CURRENCY_CONFLICT` | same as-of 複数 code / name conflict |
| `NOT_OBSERVED` | いずれか非 OBSERVED |
| `ELIGIBILITY_MISSING` | eligibility null |
| `SOURCE_INVALID` | domain/source / binding 非 CANDIDATE / ticker filter 無し |

巨大 enum 禁止。

---

## 21. Acceptance Matrix

| Case | Scenario | Verdict |
| --- | --- | --- |
| 1 | binding CANDIDATE + All Tickers ticker match + `currency_symbol=USD` + dates omitted | archive-level Layer B **CANDIDATE 可**；DailyPrice **NO**；temporal UNRESOLVED |
| 2 | `currency_symbol` missing | **FAIL**（CURRENCY_MISSING） |
| 3 | `currency_symbol=usd` | Layer A OK；Layer B = uppercase+membership 後 candidate **可**（§5） |
| 4 | `currency_symbol=US Dollar` | **FAIL**（CURRENCY_CODE_INVALID） |
| 5 | Overview/All Tickers ticker mismatch | **FAIL**（TICKER_MISMATCH） |
| 6 | Overview name vs All Tickers name 矛盾（両方非blank・不等） | **CONFLICT** |
| 7 | both date=2024 + PRICE obs 2026 | as-of evidence 可；bar/PRICE auto-apply **禁止** |
| 8 | Overview date ≠ All Tickers date | **TEMPORAL_UNRESOLVED** |
| 9 | date omitted | Forward archive-level candidate **条件付き可**；past bars **禁止** |
| 10 | current currency + old PRICE bars | past backfill **禁止** |
| 11 | `active=false` + as-of currency | evidence 保持可；current PRICE 自動適用 **禁止** |
| 12 | same as-of USD/CAD | **CONFLICT** |
| 13 | different as-of USD→CAD | temporal state difference；latest-wins **禁止** |
| 14 | `primary_exchange=XNAS` + symbol missing | USD 推測 **禁止** |

---

## 22. Mandatory answers (A–J)

| # | Question | Answer |
| --- | --- | --- |
| A | All Tickers `currency_symbol` を Trading Currency evidence に採用可能か | **YES as Layer B candidate**（ISO 検証後）。DailyPrice SOLVED ではない |
| B | ISO validation | 3 ASCII alpha + uppercase canonicalization + ISO 4217 alphabetic membership |
| C | lowercase `usd` normalize | **currency_symbol のみ** ASCII uppercase fold 可（ISO canonicalization）。name からは不可 |
| D | Overview `currency_name` 必須か | **NO**（値）。Overview OBSERVED+binding は provenance **必須** |
| E | All Tickers `currency_name` 必須か | **NO** |
| F | 3-source 最低条件 | §18 |
| G | date omitted Forward | `evidenceEligibleAt` 以降の archive-level candidate 条件付き可；全過去 bar 不可 |
| H | date supplied | compatible as-of raw/candidate；knownAt 主張不可；PRICE auto-apply 不可；不一致は TEMPORAL_UNRESOLVED |
| I | bar-level attribution | **NO-GO** |
| J | TradingCurrencyEvidence を次に実装すべきか | **YES（A）** — DailyPrice は開けない；ISO 検証を派生内に含む |

---

## 23. Separated verdicts

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

## 24. Residual blockers

| Item | Severity |
| --- | --- |
| historical knownAt / CA PIT / Universe | **Critical** |
| `DailyPrice.currency` adoption | **Critical** |
| Bar-level currency / ticker reuse history | **Critical** |
| ISO 4217 membership table + validation 実装 | **High**（次 A に内包） |
| `temporalApplicability` still UNRESOLVED | **High** |
| MIC / Venue formal resolution | **High** |
| Complete inactive/delisted universe / pagination | **High** |
| OpenFIGI ↔ Massive FIGI consistency | **High** |

---

## 25. Next（1 つのみ）

| Option | Select? |
| --- | --- |
| **A. TradingCurrencyEvidence 最小実装** | **YES** |
| B. ISO 4217 validation support 単独 | A に内包（単独ユーティリティ先行は不要） |
| C. MIC/Venue Evidence Gate | 別軸 |
| D. OpenFIGI↔Massive FIGI consistency Gate | 別軸 |
| E. ticker-history / inactive completeness | bar-level / master 用；本 blocker 最短ではない |

**選定理由:** 公式 `currency_symbol`=ISO 4217、All Tickers possession、PRICE↔Overview binding、as-of/Fail-Closed 規則が文書固定済み。次は Layer B **CANDIDATE** 派生（ISO 検証込み）の最小 class。`DailyPrice.currency` / bar-level / temporal RESOLVED は開けない。

---

## 26. Related docs

- [`trading-currency-evidence-gate.md`](trading-currency-evidence-gate.md)
- [`massive-all-tickers-forward-archive-poc.md`](massive-all-tickers-forward-archive-poc.md)
- [`massive-price-overview-binding-poc.md`](massive-price-overview-binding-poc.md)
- [`massive-price-overview-join-gate.md`](massive-price-overview-join-gate.md)
- [`massive-price-forward-archive-poc.md`](massive-price-forward-archive-poc.md)
- [`massive-ticker-overview-forward-archive-poc.md`](massive-ticker-overview-forward-archive-poc.md)
- [`forward-self-archive-design.md`](forward-self-archive-design.md)
- [`security-master-acceptance-criteria.md`](security-master-acceptance-criteria.md)

---

## 27. Merge advice

- Draft PR としてレビュー可  
- **main 自動 merge しない**  
- 本文書は `DailyPrice.currency` / SecurityId / Real Backtest / bar-level currency の許可書ではない  
- Archive-level PARTIAL ≠ Trading Currency SOLVED ≠ Layer C  
