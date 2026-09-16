# Trading Currency Evidence Gate

**Date (UTC):** 2026-09-16  
**Repository:** `siyokugo-rgb/us-stock-investment-assistant`  
**Review type:** Gate Review only（Kotlin / DailyPrice.currency / SecurityId / Backtest 実装なし）  
**Baseline `origin/main` HEAD:** `cbf335be481e0e5efa93a748377a318ab8ed3891`  
**PR #30:** MERGED（`mergedAt=2026-09-16T08:27:31Z`；merge commit = baseline）  
**PR #30 head (ancestor verified):** `3812c450f4304046d42ae496af29442476ddbe3c`

| Gate | Verdict |
| --- | --- |
| Archive-level Trading Currency Evidence | **PARTIAL** |
| Bar-level historical currency attribution | **NO-GO** |
| DailyPrice.currency | **NO-GO** |
| SecurityId | **NO-GO** |
| Venue | **PARTIAL**（本 Gate で変更しない） |
| Forward Research | **CONDITIONAL GO**（維持） |
| Real Backtest | **NO-GO**（維持） |

---

## 0. Baseline / merge confirmation

| Check | Result |
| --- | --- |
| `git fetch` + `checkout main` + `pull --ff-only` | OK |
| local `main` == `origin/main` | OK (`cbf335be481e0e5efa93a748377a318ab8ed3891`) |
| PR #30 state | **MERGED** |
| `3812c450…` is ancestor of `main` | OK |

PR #27–#30（Massive PRICE archive → Overview archive → same-vendor join Gate → binding evidence）が main に存在する前提で本 Gate を実施する。

---

## 1. Development stage

```text
Massive PRICE raw archive
  → Massive Ticker Overview raw archive
  → Same-vendor provenance join Gate
  → MassivePriceReferenceBindingEvidence (CANDIDATE; temporal UNRESOLVED)
  → 【本レビュー】Trading Currency Evidence Gate
```

**まだ進まないもの:** `DailyPrice.currency` 設定、DailyPrice 生成、SecurityId、MIC/Venue resolved、bar-level attribution、All Tickers client、OpenFIGI auto-join、Backtest、Kotlin 実装。

---

## 2. What exists (facts)

`MassivePriceReferenceBindingEvidence`（PR #30）:

- same Massive ticker namespace の archive-level provenance relation
- optional carried raw: `currencyName`, `primaryExchange`, FIGI, `active`
- `bindingEligibleAt = max(price, overview)` eligibility（≠ knownAt / as-of / bar date）
- `overviewRequestDate` = provider as-of selector（nullable）
- **`referenceTemporalApplicability = UNRESOLVED` even on CANDIDATE**
- on-disk PRICE + Overview raw integrity required

PRICE Custom Bars response 自体に currency field は **無い**（既存 PRICE PoC）。

---

## 3. Official Massive sources（調査日 UTC: 2026-09-16）

公式 docs のみ。

| Topic | Source | Recorded meaning |
| --- | --- | --- |
| Ticker Overview `currency_name` | https://massive.com/docs/rest/stocks/tickers/ticker-overview | “The name of the currency that this asset is **traded with**.” Sample: `"usd"` |
| Overview `date` | same | provider as-of selector for ticker info available on that date；≠ historical knownAt（repo PR #28/#29） |
| Overview `primary_exchange` | same | “ISO code of the primary listing exchange” |
| Overview `locale` / `market` / `type` | same | locale enum；market enum；asset type string |
| Custom Bars | https://massive.com/docs/rest/stocks/aggregates/custom-bars | OHLCV aggregates；**no currency attribute** in response schema |
| All Tickers `currency_name` | https://massive.com/docs/rest/stocks/tickers/all-tickers | same wording: “traded with” |
| All Tickers `currency_symbol` | same | “The **ISO 4217 code** of the currency that this asset is traded with.” |

**重要差分:** All Tickers は `currency_name` と **`currency_symbol`（ISO 4217）** を分離定義する。  
**Ticker Overview 公式 attribute table は `currency_name` のみ**（`currency_symbol` 非掲載）。

非公式ブログ等は採用根拠にしない。  
ADR / foreign listing / OTC に対する **currency_name 専用 caveat は Overview docs に見当たらない**（欠落 ≠ 安全証明）。

---

## 4. Currency concept separation

| Concept | This Gate |
| --- | --- |
| A. trading currency | Massive wording “traded with” に最も近い |
| B. quote currency | PRICE series の建値通貨と実質重なり得るが、公式は “quote” 語を使わない |
| C. reporting currency | **対象外**（issuer reporting） |
| D. issuer functional currency | **対象外** |
| E. settlement currency | **対象外**（docs 未言及） |
| F. account currency | **対象外** |

DailyPrice に必要なのは **PRICE series の trading/quote currency evidence**。  
公式 Overview は “traded with” のみ明示 → **trading-currency evidence candidate** として解釈可。  
reporting / settlement / account への自動同一視 **禁止**。

「`usd` に見えた」だけでは採用しない。

---

## 5. Same-vendor binding sufficiency

`MassivePriceReferenceBindingEvidence` CANDIDATE は:

> same Massive ticker namespace の 2 observations

まで。currency 採用の **必要条件の一部**であり、十分条件ではない。

### Necessary (candidate path)

1. PRICE OBSERVED + archive raw integrity  
2. Overview OBSERVED + archive raw integrity  
3. binding status `CANDIDATE`  
4. same provider ticker（requestKey-derived）  
5. both eligibility ≠ null；`bindingEligibleAt` defined  
6. `currencyName` nonblank（Overview raw）  
7. ambiguity / currency conflict 無し（§13）  
8. **temporal applicability が当該用途で成立**（現状 default **UNRESOLVED**）

### Not sufficient alone

- binding CANDIDATE without `currencyName`  
- binding CANDIDATE with `currencyName` but temporal UNRESOLVED → **DailyPrice.currency 不可**  
- US ticker / XNAS / locale=us → USD 推測  

---

## 6. Temporal applicability（最重要）

```text
bindingEligibleAt
  != overviewRequestDate
  != historical knownAt
  != bar trading date
  != permission to apply currencyName to PRICE semantics
```

### A. Overview `date` omitted

- Massive: most recent available ticker info  
- Repo: latest/provider-current reference possession  
- **禁止:** current/latest reference = historical bars 全期間へ currency 適用  
- Forward-only: `bindingEligibleAt` **以降**の research での archive-level currency **evidence candidate** は検討可  
- それでも `referenceTemporalApplicability` は現状 **UNRESOLVED** のまま（本 Gate で SOLVED にしない）

### B. Overview `date` supplied

例: Overview `date=2024-06-01`, ingest 2026, PRICE ingest 2026, `currency_name=usd`

- as-of observation の raw evidence として保持可  
- **2024 snapshot currency を 2026 PRICE へ自動適用禁止**  
- eligibility は 2026 ingest 側のまま  

---

## 7. Archive-level vs bar-level

| Layer | Verdict |
| --- | --- |
| Archive-level Forward currency evidence candidate | **PARTIAL 可**（条件 §5–6；採用 ≠ DailyPrice.currency） |
| Bar-level currency attribution to each PRICE `t` | **NO-GO** |

PRICE payload は複数 market dates を含み得る。  
bar-level には ticker/currency validity history 等が必要（未充足）。

---

## 8. Ticker change / reuse

- current `currency_name` を old PRICE bars へ逆適用 **禁止**  
- listing change / ticker reuse / ADR conversion 等で currency が変わり得る → history 無しでは recycle-proof 不可  
- Forward-only: `bindingEligibleAt` 以降の archive-level evidence candidate に限定  

---

## 9. `primary_exchange` relation

- currency は **explicit `currency_name` のみ**  
- `primary_exchange` → currency 推測 **禁止**（例: XNAS ⇒ USD）  
- venue evidence と currency evidence は **別軸**（Venue は PARTIAL 維持）  

---

## 10. FIGI granularity

- `composite_figi` / `share_class_figi` があっても  
  “currency applies to share class globally” と自動解釈 **禁止**  
- Overview currency は **その Massive ticker + as-of observation** の listing/reference evidence  
- listing-specific の可能性を残す  

---

## 11. Missing `currency_name`

Fail-Closed。禁止:

- USD 推測  
- exchange / locale / US market 推測  

概念 reason: `CURRENCY_EVIDENCE_MISSING`（実装は次工程）。

---

## 12. Conflicting currency observations

| Pattern | Treatment |
| --- | --- |
| same exact requestKey + different hash | revisionCandidate（≠ correction confirmed） |
| same ticker + different Overview `date` | temporal state difference（≠ revision） |
| same as-of / identity-bearing currency conflict | CONFLICT；latest-wins **禁止** |
| different as-of currency change | state difference；自動 continuity / Security 同一 **禁止** |

---

## 13. Normalization / ISO 4217

| Fact | Implication |
| --- | --- |
| Overview sample `currency_name` = `"usd"`（lowercase name-like） | 表示名寄り |
| All Tickers `currency_symbol` = ISO 4217 code（公式明示） | ISO path は **symbol** 側 |
| Overview docs に `currency_symbol` 無し | Overview `currency_name` alone → ISO 4217 正規化の公式根拠 **不足** |

**判定:** Overview `currency_name` からの無根拠 ISO normalize **禁止**。  
将来 All Tickers `currency_symbol` 等の明示 ISO field を possession した場合に再評価。  
今回コード実装なし。

---

## 14. Acceptance Matrix

| Case | Scenario | Verdict |
| --- | --- | --- |
| 1 | valid binding + `currency_name` + date omitted | archive-level Forward currency **evidence candidate PARTIAL 可**；DailyPrice.currency **NO**；temporal still UNRESOLVED |
| 2 | valid binding + date=2024 + PRICE=2026 | currency auto-apply **禁止** |
| 3 | `currency_name` missing | **FAIL**（CURRENCY_EVIDENCE_MISSING） |
| 4 | same ticker / same as-of / conflicting currency | **CONFLICT**；latest-wins 禁止 |
| 5 | same ticker / different as-of / currency changed | **temporal state difference** |
| 6 | `primary_exchange` あり / `currency_name` なし | currency 推測 **禁止** |
| 7 | US ticker | USD 推測 **禁止** |
| 8 | FIGI 一致 but currency conflict | latest-wins **禁止** |
| 9 | old bars + current Overview currency | historical backfill **禁止** |
| 10 | forward observation after `bindingEligibleAt` | archive-level evidence candidate **条件付き可**；bar-level / DailyPrice **不可** |

---

## 15. Mandatory answers (A–I)

| # | Question | Answer |
| --- | --- | --- |
| A | `currency_name` は公式上 trading currency evidence として使えるか | **YES as evidence candidate** — “traded with”。reporting/settlement ではない。DailyPrice.currency SOLVED ではない |
| B | same-vendor binding CANDIDATE だけで十分か | **NO** — nonblank currency + conflict-free + temporal rules が追加必須 |
| C | Overview date omitted なら Forward PRICE へ使えるか | archive-level evidence candidate は `bindingEligibleAt` 以降で **PARTIAL 可**；historical bars 全適用 **不可** |
| D | date 付き Overview | as-of raw evidence；PRICE へ自動適用 **禁止** |
| E | bar-level currency attribution | **NO-GO** |
| F | currency_name 欠損 | Fail-Closed；推測禁止 |
| G | currency conflict | latest-wins 禁止；revision vs temporal state 分離 |
| H | ISO 4217 normalization | Overview `currency_name` alone では **不可**（All Tickers `currency_symbol` が ISO 明示；Overview 未掲載） |
| I | 次に最小実装へ進めるか | **YES → A. TradingCurrencyEvidence 最小実装**（DailyPrice.currency は開けない） |

---

## 16. Separated verdicts

| Gate | Verdict |
| --- | --- |
| Archive-level Trading Currency Evidence | **PARTIAL** |
| Bar-level historical currency | **NO-GO** |
| DailyPrice.currency | **NO-GO** |
| SecurityId | **NO-GO** |
| Venue | **PARTIAL** |
| Forward Research | **CONDITIONAL GO** |
| Real Backtest | **NO-GO** |

---

## 17. Residual blockers

| Item | Severity |
| --- | --- |
| historical knownAt / CA PIT / Universe | **Critical** |
| `DailyPrice.currency` adoption（temporal + ISO form） | **Critical** |
| Bar-level currency / ticker reuse history | **Critical** |
| Overview に ISO `currency_symbol` 無し | **High**（normalize blocker） |
| MIC / Venue formal resolution | **High** |
| All Tickers inactive/delisted completeness | **High** |
| OpenFIGI ↔ Massive FIGI consistency | **High** |

---

## 18. Next（1 つのみ）

| Option | Select? |
| --- | --- |
| **A. TradingCurrencyEvidence 最小実装** | **YES** |
| B. MIC/Venue Evidence Gate | 別軸 |
| C. All Tickers inactive/delisted archive | complete master / 将来 ISO path 候補だが本 blocker 最短ではない |
| D. OpenFIGI↔Massive FIGI consistency Gate | 別軸 |
| E. currency source再調査 | Overview “traded with” は十分；ISO は All Tickers 側で既知 — 再調査より evidence 実装を優先 |

**選定理由:** 公式 “traded with” + binding CANDIDATE + Fail-Closed 規則は文書固定済み。次は **DailyPrice.currency を開けない** TradingCurrencyEvidence（raw `currencyName` 保持、temporal UNRESOLVED 維持、推測禁止）の最小実装。

実装時も維持:

- `referenceTemporalApplicability = UNRESOLVED`  
- no ISO normalize from `currency_name` alone  
- no bar-level attribution  
- no past backfill  

---

## 19. Related docs

- [`massive-price-overview-join-gate.md`](massive-price-overview-join-gate.md)
- [`massive-price-overview-binding-poc.md`](massive-price-overview-binding-poc.md)
- [`massive-ticker-overview-forward-archive-poc.md`](massive-ticker-overview-forward-archive-poc.md)
- [`massive-price-forward-archive-poc.md`](massive-price-forward-archive-poc.md)
- [`forward-self-archive-design.md`](forward-self-archive-design.md)
- [`security-master-acceptance-criteria.md`](security-master-acceptance-criteria.md)
- [`venue-listing-identity-gate-review.md`](venue-listing-identity-gate-review.md)

---

## 20. Merge advice

- Draft PR としてレビュー可  
- **main 自動 merge しない**  
- 本文書は DailyPrice.currency / SecurityId / Real Backtest / bar-level currency の許可書ではない  
- Archive-level PARTIAL ≠ Trading Currency SOLVED  
