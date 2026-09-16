# Massive PRICE ↔ Ticker Overview Same-Vendor Binding Evidence PoC

**Date (UTC):** 2026-09-16  
**Baseline SHA:** `fc85537acba060799b42f9f4557b47ce68ed839b` (`origin/main` verified)  
**PR #29:** MERGED（ancestor `cf76e18f…` verified）  
**Gate SoT:** [`massive-price-overview-join-gate.md`](massive-price-overview-join-gate.md)  
**Scope:** archive-level pure derived provenance evidence only

| Gate | Status |
| --- | --- |
| Archive-level Massive same-vendor binding evidence | **PASS（本 PoC）** |
| Reference field temporal applicability | **UNRESOLVED**（CANDIDATE でも維持） |
| Trading Currency / Venue adoption | **PARTIAL**（維持；本 PoC で SOLVED にしない） |
| Bar-level historical identity | **NO-GO** |
| SecurityId / DailyPrice | **NO-GO** |
| Forward Research | **CONDITIONAL GO**（維持） |
| Real Backtest | **NO-GO**（維持） |

---

## Purpose

OBSERVED Massive PRICE（`massive.stocks.aggs_1d.unadjusted`）と OBSERVED Massive Ticker Overview（`massive.stocks.ticker_overview`）から、

> 同一 Massive provider ticker の **archive-level provenance relation**

だけを pure derived evidence として生成する。

**目的ではないもの:** Security identity、DailyPrice、Trading Currency / Venue 採用、bar-level attribution。

---

## Model

`MassivePriceReferenceBindingEvidence`（DB なし）

| Field | Meaning |
| --- | --- |
| `priceArchiveId` / `overviewArchiveId` | OBSERVED archive ids |
| `provider` | `Massive` |
| `providerTicker` | **PRICE requestKey** から strict parse（caller 自由 ticker 禁止） |
| `priceEligibilityBoundaryAt` / `overviewEligibilityBoundaryAt` | archive eligibility |
| `bindingEligibleAt` | `max(price, overview)` = binding usable time |
| `overviewRequestDate` | Overview requestKey の optional `date`（provider as-of selector） |
| `currencyName` / `primaryExchange` / `compositeFigi` / `shareClassFigi` / `active` | Overview raw evidence carried（optional） |
| `referenceTemporalApplicability` | always **`UNRESOLVED`** on CANDIDATE |
| `status` | `CANDIDATE` / `INELIGIBLE` / `CONFLICT` |
| `reason` | INELIGIBLE/CONFLICT 時必須 |

### Status / reason（最小）

Reasons: `PRICE_NOT_OBSERVED`, `OVERVIEW_NOT_OBSERVED`, `PRICE_ELIGIBILITY_MISSING`, `OVERVIEW_ELIGIBILITY_MISSING`, `PRICE_REQUEST_KEY_INVALID`（throw Fail-Closed）, `OVERVIEW_REQUEST_KEY_INVALID`, `TICKER_MISMATCH`, `OVERVIEW_RAW_INVALID`, `SOURCE_MISMATCH`, `TEMPORAL_CONFLICT`（reserved）。

---

## Derivation rules

1. PRICE: `domain=PRICE`, `source=massive.stocks.aggs_1d.unadjusted`, OBSERVED, eligibility ≠ null  
2. Overview: `domain=SECURITY_MASTER`, `source=massive.stocks.ticker_overview`, OBSERVED, eligibility ≠ null  
3. `MassiveDailyAggsRequestKey` / `MassiveTickerOverviewRequestKey` strict parse  
4. case-sensitive exact ticker match  
5. Overview `rawPayloadUri` を **必ず on-disk 読込** → SHA-256 == `rawPayloadHash` → `MassiveTickerOverviewArchiveValidator` 再検証 PASS；validated ticker 一致  
6. PRICE も同様に on-disk raw 存在 + SHA-256 == `rawPayloadHash`（semantic 全面再parseはしない）  
7. CANDIDATE: `bindingEligibleAt = max(...)`；`referenceTemporalApplicability = UNRESOLVED`；`reason = null`

**禁止:** caller-supplied response bytes で archived raw を迂回すること。`derive(price, overview)` のみ。

missing / unreadable / hash mismatch → `ArchiveValidationException` Fail-Closed（巨大 reason enum 追加なし）。

### Prohibited promotion

| Carried field | Forbidden elevation |
| --- | --- |
| `currencyName` | `DailyPrice.currency` |
| `primaryExchange` | MIC / venue resolved |
| FIGI fields | SecurityId |
| `active` | Security existence absolute |
| `overviewRequestDate` | eligibility / knownAt / bar attribution |

---

## PIT reminders

```text
bindingEligibleAt
  != overviewRequestDate
  != historical knownAt
  != bar trading date
```

Example: Overview `date=2024-06-01`, ingest 2026 → CANDIDATE with `bindingEligibleAt` on 2026 side；2024 metadata の 2026 PRICE 自動適用 **禁止**（UNRESOLVED）。

---

## Tests

`MassivePriceReferenceBindingEvidenceTest` — CANDIDATE / INELIGIBLE paths、requestKey 導出、hash Fail-Closed、date as-of、max eligibility、non-generation of SecurityId/DailyPrice/knownAt/bar fields。

既存 Massive PRICE / Overview / OpenFIGI `ProviderSymbolBindingEvidence` regression は full `test` で維持。

---

## Residual blockers

| Item | Severity |
| --- | --- |
| historical knownAt / CA PIT / Universe | Critical |
| Trading Currency → DailyPrice（temporal applicability） | Critical / PARTIAL |
| Bar-level identity / ticker reuse | Critical |
| MIC / Venue formal resolution | High |
| All Tickers inactive/delisted completeness | High |
| OpenFIGI ↔ Massive FIGI consistency | High |

---

## Next（自動進行しない）

本 PoC PASS 後に再評価:

A. Trading Currency Evidence Gate — 文書: [`trading-currency-evidence-gate.md`](trading-currency-evidence-gate.md)  
B. MIC / Venue Evidence Gate  
C. All Tickers inactive/delisted archive  
D. OpenFIGI↔Massive FIGI consistency  

---

## Merge advice

- Draft PR としてレビュー可  
- **main 自動 merge しない**  
- CANDIDATE ≠ Trading Currency SOLVED ≠ Venue SOLVED ≠ SecurityId / DailyPrice GO  
