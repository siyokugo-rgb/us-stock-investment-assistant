# Trading Currency Evidence PoC

**Date (UTC):** 2026-09-17  
**Baseline `origin/main` HEAD:** `efba329c337a5fdb451a01ec7524b5026c36a01d`  
**PR #33:** MERGED（final HEAD `80e4efab…` ancestor verified）  
**Gate SoT:** [`massive-currency-evidence-gate.md`](massive-currency-evidence-gate.md)

| Gate | Status |
| --- | --- |
| TradingCurrencyEvidence (PRICE + All Tickers) derived model | **PASS（本 PoC）** |
| Raw currency evidence | **PASS** |
| Validated ISO currency evidence | **PARTIAL**（candidate 生成可；DailyPrice 未採用） |
| Archive-level Trading Currency | **PARTIAL** |
| Bar-level historical currency | **NO-GO** |
| DailyPrice.currency | **NO-GO** |
| SecurityId / MIC / Venue | **NO-GO / PARTIAL** |
| Forward Research | **CONDITIONAL GO** |
| Real Backtest | **NO-GO** |

---

## Purpose

既存 Massive PRICE archive + Massive All Tickers archive から、archive-level **Trading Currency evidence candidate（Layer B）** を pure derived model として生成する。

**必須コア:** PRICE + All Tickers のみ。  
**Overview:** 今回入力にしない（optional corroboration は未実装）。

---

## Model

`archive.poc.binding.TradingCurrencyEvidence`

| Field | Notes |
| --- | --- |
| `priceArchiveId` / `allTickersArchiveId` | required |
| `provider` | `Massive` |
| `providerTicker` | **PRICE requestKey** のみから導出（caller 自由 ticker 禁止） |
| `rawCurrencySymbol` | All Tickers exact raw |
| `canonicalCurrencyCode` | CANDIDATE のみ；**== rawCurrencySymbol**（repair なし） |
| `priceEligibilityBoundaryAt` / `allTickersEligibilityBoundaryAt` | |
| `evidenceEligibleAt` | `max(price, allTickers)` usable time only |
| `allTickersRequestDate?` | requestKey as-of；≠ knownAt / eligibility |
| `temporalApplicability` | always **UNRESOLVED** on CANDIDATE |
| `status` / `reason?` | CANDIDATE / INELIGIBLE / CONFLICT |

Deriver: `TradingCurrencyEvidenceDeriver.derive(priceRecord, allTickersRecord)`.

---

## PRICE conditions

- domain=`PRICE`, source=`massive.stocks.aggs_1d.unadjusted`
- OBSERVED + eligibility ≠ null
- requestKey → `MassiveDailyAggsRequestKey.parseOrThrow`（malformed → **throw** Fail-Closed）
- on-disk raw exists + SHA-256 == `rawPayloadHash`

---

## All Tickers conditions

- domain=`SECURITY_MASTER`, source=`massive.stocks.all_tickers`
- OBSERVED + eligibility ≠ null
- requestKey → `MassiveAllTickersRequestKey.parseOrThrow`
- **`ticker=` filter 明示**（無し → INELIGIBLE `SOURCE_MISMATCH`）
- PRICE ticker == All Tickers ticker（case-sensitive）
- raw integrity + `MassiveAllTickersArchiveValidator` 再検証
- `resultCount == 1`（intended ticker 一意）

---

## currency_symbol / ISO validation

1. present / nonblank → else `CURRENCY_MISSING`  
2. raw matches **`^[A-Z]{3}$`** → else `CURRENCY_CODE_INVALID`（`usd` / `US Dollar` / `840` 含む）  
3. ISO 4217 alphabetic membership → else `CURRENCY_CODE_INVALID`  

### ISO membership 実装方式

`Iso4217AlphabeticCodes` → `java.util.Currency.getAvailableCurrencies()` の `currencyCode` exact set membership。

- **更新責務:** JVM/Android の available-currency データに追随（手メンテ code list なし）  
- **必須順序:** regex `^[A-Z]{3}$` を先に通す。lowercase を membership lookup に渡して補正させない  
- **自動 uppercase repair 禁止**  
- **禁止:** `Currency.getInstance(code)` 成功を membership とみなさない（Android では `getInstance("ABC")` が成功しうる）

CANDIDATE invariant: `canonicalCurrencyCode == rawCurrencySymbol`.

**CANDIDATE model invariant itself enforces ISO 4217 membership**（`Iso4217AlphabeticCodes`）；Deriver validation alone に依存しない。`ABC` / `usd` の direct construction は Fail-Closed。

---

## temporal / date

- `temporalApplicability = UNRESOLVED` even on CANDIDATE  
- CANDIDATE ≠ bar-level / DailyPrice.currency usable  
- `allTickersRequestDate` 保持可；eligibility / knownAt / past bar attribution に使わない  

---

## Forbidden

DailyPrice.currency / DailyPrice / SecurityId / MIC / Venue resolved / bar-level attribution / past backfill / Overview required input / OpenFIGI join / DB / Backtest / Android.

---

## Tests

`TradingCurrencyEvidenceTest` — USD CANDIDATE、`usd` INVALID、ticker mismatch、raw integrity Fail-Closed、filter 無し、date 非遡及、regression suite 含む。

---

## Residual blockers

| Item | Severity |
| --- | --- |
| historical knownAt / CA PIT / Universe | **Critical** |
| DailyPrice.currency adoption | **Critical** |
| Bar-level currency / ticker reuse | **Critical** |
| temporalApplicability UNRESOLVED | **High** |
| Overview optional corroboration | **High**（意図的未実装） |
| MIC / Venue / FIGI | **High** |

---

## Next

**次の単一工程:** [`trading-currency-temporal-applicability-gate.md`](trading-currency-temporal-applicability-gate.md)（本 Gate Review）。

Overview optional corroboration、Venue/MIC Gate は別軸。
**DailyPrice.currency はまだ開けない。**

---

## Merge advice

Draft レビュー可。main 自動 merge しない。  
本 PoC PASS ≠ DailyPrice.currency GO ≠ Real Backtest GO。  
