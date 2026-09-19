# Ticker Events ↔ Overview share_class corroboration PoC

**Date (UTC):** 2026-09-19  
**Baseline `origin/main` HEAD:** `2559ce113df01ad9e3514f79d2dcc39dc3f55bfe`  
**Baseline `origin/main` tree:** `277a12942255c1756465203596975f926e3d1560`  
**PR #45:** MERGED（Ticker Events live schema probe `LIVE_SCHEMA_VERIFIED`）  
**PR #46:** Draft（本 PoC）  
**Mode:** synthetic / offline first（**live API NOT RUN；request 禁止**）

| Gate | Status |
| --- | --- |
| Overview TICKER_CHANGE_CANDIDATE ↔ Events window corroboration（synthetic） | **PASS（本 PoC）** |
| Live corroboration validation | **NOT YET RUN** |
| SecurityId / SecurityIdentifier / knownAt / validFrom·validTo | **NO-GO** |
| Events-only ticker-change candidate invention | **NO-GO** |
| DailyPrice / Real Backtest | **NO-GO** |

---

## Purpose

既存 `SecurityIdentityContinuityEvidenceDeriver` が Overview T1/T2 から
`TICKER_CHANGE_CANDIDATE` を出した場合に限り、Massive Ticker Events archive を
**独立補助 evidence** として照合する。

目的: Overview cross-time candidate と Events raw evidence の整合を Fail-Closed で見る。  
Events から ticker-change candidate 自体は **新規生成しない**。

---

## Inputs

| Input | Allowed |
| --- | --- |
| Overview ↔ Overview | YES |
| All Tickers | **NO**（`OVERVIEW_SOURCE_REQUIRED`） |
| Ticker Events OBSERVED archive | YES（補助） |

Reuse（変更なし）:

- `SecurityIdentityContinuityEvidenceDeriver` / Evidence
- `MassiveTickerEventsArchiveValidator` / RequestKey
- `MassiveTickerOverviewArchiveValidator` / RequestKey
- `ManifestRecord` / `Sha256Hex`

---

## Model

Package: `archive.poc.binding`

- `TickerEventOverviewCorroborationEvidence`
- `TickerEventOverviewCorroborationDeriver`

### Status

| Status | Meaning |
| --- | --- |
| `CORROBORATED_TICKER_CHANGE_CANDIDATE` | Overview TICKER_CHANGE_CANDIDATE + exactly one window match |
| `UNRESOLVED` | それ以外（absence / ambiguous / non-candidate 等） |

`CONFLICT` は今回追加しない（Events completeness/order/direction semantics 未確定）。

### Matching rule

既存 continuity candidate（first=earlier as-of, second=later）に対し:

```text
event.type == ticker_change
event.ticker == secondProviderTicker
firstProviderAsOfDate < event.date <= secondProviderAsOfDate
```

Window: **`(T1, T2]`**

| Match count | Result |
| --- | --- |
| 1 | CORROBORATED |
| 0 | UNRESOLVED `NO_EVENT_WINDOW_MATCH` |
| >1 | UNRESOLVED `EVENT_WINDOW_MATCH_AMBIGUOUS`（dedupe 禁止） |

**非意味:**

- event date = effective date 確定
- event date = knownAt / validFrom / validTo
- Events 配列順 → OLD/NEW
- lookup id == second ticker（identity）
- Security continuity 確定

### Lookup id

`tickerEventsLookupId` = requestKey provenance only。  
`lookupId == secondProviderTicker` を要求しない（Ticker / CUSIP / Composite FIGI 非推測）。

### Eligibility

`corroborationEligibleAt = max(continuity.evidenceEligibleAt, events.eligibilityBoundaryAt)`  
= combined **evidence availability** only（≠ knownAt / validity）。

---

## Synthetic results（SQ → XYZ）

| Item | Value |
| --- | --- |
| Overview T1 | ticker=`SQ` date=`2025-01-17` same share_class |
| Overview T2 | ticker=`XYZ` date=`2025-01-22` same share_class |
| Continuity | `TICKER_CHANGE_CANDIDATE` |
| Events | `[ticker_change/2025-01-21/XYZ, ticker_change/2015-11-18/SQ]` |
| Match | only `2025-01-21/XYZ` in `(2025-01-17, 2025-01-22]` |
| Corroboration | **CORROBORATED_TICKER_CHANGE_CANDIDATE** |

2015 SQ event / 配列位置は OLD 判定に未使用。

---

## Live validation status

| Claim | Status |
| --- | --- |
| Synthetic corroboration semantics | **PASS（本 PoC）** |
| Live Overview+Events corroboration | **NOT YET RUN** |
| Provider request this PR | **禁止** |

---

## Residual blockers

| Item | Severity |
| --- | --- |
| Live corroboration 未実施 | **Critical** |
| SecurityId issuance | **Critical**（NO-GO） |
| knownAt / PIT | **Critical**（event date ≠ knownAt） |
| Events completeness / direction semantics | **High** |
| experimental Events schema drift | **High** |

---

## Next（1 つのみ）

| Option | Select? |
| --- | --- |
| A. Live Ticker Events schema probe | **DONE（PR #45）** |
| B. Ticker Events ↔ Overview share_class corroboration PoC（synthetic） | **DONE（本 PR）** |
| **C. Live corroboration validation**（Overview dated SQ/XYZ + Events XYZ；無料枠） | **YES** |
| D. SecurityId issuance | **禁止** |

選定理由: synthetic Fail-Closed は固定済み。blocking は実 archive での CORROBORATED 再現。

---

## Related docs

- [`massive-ticker-events-forward-archive-poc.md`](massive-ticker-events-forward-archive-poc.md)
- [`massive-ticker-events-gate.md`](massive-ticker-events-gate.md)
- [`security-identity-continuity-evidence-poc.md`](security-identity-continuity-evidence-poc.md)
- [`security-identity-ticker-reuse-gate.md`](security-identity-ticker-reuse-gate.md)

---

## Merge advice

- Draft PR としてレビュー可
- **Ready / merge はユーザー指示まで禁止**
- live API を本 PR で実行しない
- 本文書は SecurityId / knownAt / continuity GO の許可書ではない
