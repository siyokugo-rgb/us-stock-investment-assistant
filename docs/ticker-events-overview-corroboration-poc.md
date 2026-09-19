# Ticker Events ↔ Overview share_class corroboration PoC

**Date (UTC):** 2026-09-19  
**Baseline `origin/main` HEAD:** `b60878600baa434245b242fbdddc8b2db1700226`（PR #46 MERGED）  
**PR #46:** MERGED（synthetic corroboration PoC）  
**PR #47:** Draft（live corroboration validation runner）  
**Mode:** synthetic PASS + live runner ready（**this environment: MASSIVE_API_KEY=NOT SET → provider request 0**）

| Gate | Status |
| --- | --- |
| Overview TICKER_CHANGE_CANDIDATE ↔ Events window corroboration（synthetic） | **PASS（PR #46）** |
| Ticker Events lookup scope = later ticker lookup only | **PASS（PR #46）** |
| Live corroboration validation runner / offline QA | **PASS（PR #47）** |
| Live provider corroboration（SQ/XYZ + Events XYZ） | **NOT RUN**（`MASSIVE_API_KEY=NOT SET`） |
| CUSIP / Composite FIGI lookup corroboration | **NO-GO / deferred** |
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
| All Tickers / 非 Overview | **NO**（`OVERVIEW_SOURCE_REQUIRED`；continuity deriver 未呼び出し・raw 未読込） |
| Ticker Events OBSERVED archive | YES（補助；ticker lookup only） |

Reuse（変更なし）:

- `SecurityIdentityContinuityEvidenceDeriver` / Evidence
- `MassiveTickerEventsArchiveValidator` / RequestKey
- `MassiveTickerOverviewArchiveValidator` / RequestKey
- `ManifestRecord` / `Sha256Hex`
- Overview / Events ForwardArchiveService + clients

---

## Model

Package: `archive.poc.binding`

- `TickerEventOverviewCorroborationEvidence`
- `TickerEventOverviewCorroborationDeriver`
- `TickerEventOverviewCorroborationLivePoc`（live runner）

### Status

| Status | Meaning |
| --- | --- |
| `CORROBORATED_TICKER_CHANGE_CANDIDATE` | Overview TICKER_CHANGE_CANDIDATE + ticker-lookup provenance + exactly one window match |
| `UNRESOLVED` | それ以外（absence / ambiguous / non-candidate / non-ticker lookup 等） |

`CONFLICT` は今回追加しない（Events completeness/order/direction semantics 未確定）。

### Matching rule

既存 continuity candidate（first=earlier as-of, second=later）に対し:

```text
Ticker Events lookup:
  tickerEventsLookupId == secondProviderTicker
  （ticker lookup のみ；CUSIP / Composite FIGI = deferred）

event.type == ticker_change
event.ticker == secondProviderTicker
firstProviderAsOfDate < event.date <= secondProviderAsOfDate
```

Window: **`(T1, T2]`**

| Match count | Result |
| --- | --- |
| lookup ≠ later ticker | UNRESOLVED `EVENT_LOOKUP_NOT_LATER_TICKER` |
| 1（かつ lookup == later ticker） | CORROBORATED |
| 0 | UNRESOLVED `NO_EVENT_WINDOW_MATCH` |
| >1 | UNRESOLVED `EVENT_WINDOW_MATCH_AMBIGUOUS`（dedupe 禁止） |

**非意味:**

- event date = effective date 確定
- event date = knownAt / validFrom / validTo
- Events 配列順 → OLD/NEW
- `tickerEventsLookupId == secondProviderTicker` = SecurityId / Security identity equality
- Security continuity 確定

### Lookup id（PoC scope）

`tickerEventsLookupId` = requestKey provenance only。

**Current PoC = ticker lookup only:**

- corroboration へ進める最低条件: `tickerEventsLookupId == continuity.secondProviderTicker`
- 不一致 → `UNRESOLVED` / `EVENT_LOOKUP_NOT_LATER_TICKER`
- この equality は **request provenance restriction** のみ
- **≠** SecurityId / Security identity proof

**Deferred:** CUSIP / Composite FIGI lookup corroboration（namespace 安全化後）

### Non-Overview fail-closed

Overview 以外 → `UNRESOLVED` / `OVERVIEW_SOURCE_REQUIRED`（continuity deriver 未呼び出し・raw 未読込）。

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
| Events lookup | `XYZ`（== secondProviderTicker） |
| Events | `[ticker_change/2025-01-21/XYZ, ticker_change/2015-11-18/SQ]` |
| Match | only `2025-01-21/XYZ` in `(2025-01-17, 2025-01-22]` |
| Corroboration | **CORROBORATED_TICKER_CHANGE_CANDIDATE** |

---

## Live runner

| Item | Value |
| --- | --- |
| Entry | `archive.poc.binding.TickerEventOverviewCorroborationLivePoc` |
| Gradle | `./gradlew --no-daemon -q massiveTickerEventsOverviewLiveCorroborationPoc` |
| T1 | Overview `SQ` / `2025-01-17` |
| T2 | Overview `XYZ` / `2025-01-22` |
| Events | lookupId=`XYZ` |
| Max requests | **3**（retry 禁止・穴埋め禁止） |
| Offline QA | 9 classification tests |

### Live classification

| Class | Meaning |
| --- | --- |
| `LIVE_CORROBORATED` | 3× OBSERVED + ingest OK + continuity `TICKER_CHANGE_CANDIDATE` + corroboration `CORROBORATED_*` |
| `LIVE_UNRESOLVED` | 3 archive 成立だが corroboration `UNRESOLVED`（reason そのまま） |
| `LIVE_PROVIDER_FAILURE` | いずれか `PROVIDER_FAILURE` |
| `LIVE_VALIDATION_REJECTED` | いずれか `REJECTED_VALIDATION` |
| `LIVE_LOCAL_FAILURE` | local/integrity/deriver exception 等 |
| `LIVE_UNVERIFIED` | API key 無し等で provider request 未実施 |

Claim boundary（`LIVE_CORROBORATED` でも）: ≠ SecurityId / knownAt / validFrom·validTo / DailyPrice / Backtest GO。

---

## Latest live validation

| Item | Value |
| --- | --- |
| executedAt (UTC) | **NOT RUN**（2026-09-19；this Cloud Agent environment） |
| MASSIVE_API_KEY | **NOT SET**（値禁止；値未保有） |
| request count | **0** |
| T1 / T2 / Events | not requested |
| continuity / corroboration | n/a |
| liveClassification | would be `LIVE_UNVERIFIED` if runner invoked without key |
| raw SHA recheck | n/a |
| raw Git 未収録 | n/a（provider raw 未取得） |
| provider failure | n/a |

**Blocking:** `MASSIVE_API_KEY` を SET した環境で同 runner を **1 回のみ**実行する。

---

## Residual blockers

| Item | Severity |
| --- | --- |
| Live provider corroboration 未実施（API key 不在） | **Critical** |
| SecurityId issuance | **Critical**（NO-GO） |
| knownAt / PIT | **Critical**（event date ≠ knownAt） |
| CUSIP / Composite FIGI lookup corroboration | **High**（deferred） |
| Events completeness / direction semantics | **High** |
| experimental Events schema drift | **High** |

---

## Next（1 つのみ）

| Option | Select? |
| --- | --- |
| A. Live Ticker Events schema probe | **DONE（PR #45）** |
| B. Ticker Events ↔ Overview share_class corroboration PoC（synthetic） | **DONE（PR #46）** |
| C. Live corroboration runner / offline QA | **DONE（PR #47 Draft；key 未設定で provider 未実行）** |
| **D. Re-run live corroboration with MASSIVE_API_KEY set**（SQ/XYZ + Events XYZ；1 回・max 3） | **YES** |
| E. SecurityId issuance | **禁止** |

選定理由: runner は固定済み。blocking は key 付き環境での実 CORROBORATED 再現。

---

## Related docs

- [`massive-ticker-events-forward-archive-poc.md`](massive-ticker-events-forward-archive-poc.md)
- [`massive-ticker-events-gate.md`](massive-ticker-events-gate.md)
- [`security-identity-continuity-evidence-poc.md`](security-identity-continuity-evidence-poc.md)
- [`security-identity-ticker-reuse-gate.md`](security-identity-ticker-reuse-gate.md)

---

## Merge advice

- Draft PR #47 としてレビュー可
- **Ready / merge はユーザー指示まで禁止**
- live API は key SET 環境で 1 回のみ（本環境では未実施）
- 本文書は SecurityId / knownAt / continuity GO の許可書ではない
