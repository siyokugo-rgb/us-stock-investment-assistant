# Massive Ticker Events Forward Archive PoC

**Date (UTC):** 2026-09-18
**Baseline `origin/main` HEAD:** `fb19b2a968f233317a0f015fd5c09128dfde7c54`
**Baseline `origin/main` tree:** `6b1346faa9794759ea49c87c54c6942407f5eeee`
**Semantics Gate:** [`massive-ticker-events-gate.md`](massive-ticker-events-gate.md)（PR #43 MERGED）
**Contract SoT:** [`forward-self-archive-design.md`](forward-self-archive-design.md)
**Mode:** synthetic / offline QA first（**live API NOT YET RUN**）

| Gate | Status |
| --- | --- |
| Ticker Events forward raw archive boundary（synthetic） | **PASS（本 PoC）** |
| Live schema probe / live archive validation | **NOT YET RUN** |
| Security continuity / SecurityId / knownAt / validity | **NO-GO** |
| DailyPrice.currency / Real Backtest | **NO-GO** |
| Ticker Events client domain adoption | **NO-GO** |

---

## Purpose

Massive Stocks **Ticker Events**（experimental）の HTTP response exact bytes を、forward-only **security/reference timeline** evidence として保存する最小境界。

**目的ではないもの:** SecurityId / SecurityIdentifier / knownAt / validFrom·validTo / OLD→NEW 確定 / continuity deriver / live runner。

---

## Endpoint / Source

| Item | Value |
| --- | --- |
| Provider | Massive |
| Docs | https://massive.com/docs/rest/stocks/corporate-actions/ticker-events |
| Method/Path | `GET /vX/reference/tickers/{id}/events` |
| Status | **experimental** |
| Query（本 PoC） | `types=ticker_change`（固定） |
| Auth | env `MASSIVE_API_KEY` → query `apiKey` |
| Domain | `SECURITY_MASTER` |
| Source | `massive.stocks.ticker_events` |

### Free-tier

公式: Stocks Basic **Included**；history **2 years**。有料プラン前提にしない。
本 revision は live 未実施（access は Gate の公式表に依拠）。

---

## Exact raw possession

既存 Overview / All Tickers と同型:

1. body 完全受信 → SHA-256
2. `ImmutableRawStore` へ exact bytes
3. Fail-Closed validator
4. `ManifestStore` append
5. OBSERVED のみ `eligibilityBoundaryAt = ingestedAt`

HTTP 4xx/5xx → `PROVIDER_FAILURE`（body あれば raw 保存）。
transport failure → `PROVIDER_FAILURE`（fetchedAt/raw なし）。
local write/manifest failure → `LOCAL_ARCHIVE_FAILURE`。

共通基盤（`ManifestRecord` / `ObservationStatus` / `MassiveHttpPossession` / `ImmutableRawStore` / `ManifestStore` / `CoverageCalculator` / `Sha256Hex`）は **変更しない**。

---

## requestKey（secret-free）

```text
GET|/vX/reference/tickers/{LOOKUP_ID}/events|types=ticker_change
```

- `LOOKUP_ID` は opaque provider lookup id（Ticker/CUSIP/Composite FIGI を **推測しない**）
- `|` `/` `?` `#` `&` を含む id は Fail-Closed
- `apiKey` segment **禁止**
- `MassiveTickerEventsRequestKey` が canonical のみ許可

---

## Validator policy

HTTP 2xx だけでは OBSERVED にしない。strict UTF-8 + JSON + 意味境界。

### Root optional vs OBSERVED policy

公式 Response Attributes では `request_id` / `results` / `results.events` / `results.name` / `status` は **optional**。

本 PoC の OBSERVED 昇格（公式 required 主張ではない）:

| Required for OBSERVED | Rule |
| --- | --- |
| `status` | string `"OK"` |
| `results` | object（null 不可） |
| `results.events` | array（null 不可） |

欠落時 → `REJECTED_VALIDATION`（raw 保存）。
`next_url` present → `REJECTED_VALIDATION`（complete archive 未保証）。

### Empty events

`results.events = []` → 構造上 **OBSERVED** 可。
notes で必ず: empty observed / no-event meaning **UNKNOWN** / absence ≠ no change / completeness 未証明 / **negative proof 禁止**。
MISSING 扱いしない。Security continuity 判定しない。

### Non-empty event（Fail-Closed usable evidence）

sample 観測 keys のみ使用。各 event 受理条件（公式 required 主張ではない）:

- object
- `type` nonblank == `ticker_change`
- `date` nonblank + **valid calendar** `YYYY-MM-DD`（`LocalDate.parse`；`2026-02-30` reject）
- `ticker_change` object
- `ticker_change.ticker` nonblank string

### Forbidden in validator

old/new 生成 / OLD→NEW 確定 / sort / dedupe / chain / Security continuity / SecurityId / SecurityIdentifier / knownAt / validFrom·validTo。
raw 配列順序を保持。duplicate / 同日異 ticker は raw 保持；notes に `potential duplicate/conflict; preserved` 可。
未知 field は business 使用禁止；既知安全なら自動 reject しない。

---

## Manifest

OBSERVED でも:

- `externalIdentifier = null`
- `externalIdentifierNamespace = null`
- knownAt / SecurityId / SecurityIdentifier / validFrom / validTo **生成なし**

notes 最低限: raw evidence only / lookup id ≠ SecurityId / ticker_change.ticker ≠ SecurityId / event date ≠ knownAt·validity / ingestedAt·eligibility ≠ event date / no continuity inference / empty ≠ negative proof。

---

## Implementation

| File | Role |
| --- | --- |
| `MassiveTickerEventsArchiveClient.kt` | HTTP possession |
| `MassiveTickerEventsRequestKey.kt` | canonical parse |
| `MassiveTickerEventsArchiveValidator.kt` | Fail-Closed + raw evidence DTO |
| `MassiveTickerEventsForwardArchiveService.kt` | archive orchestration |

Live runner: **未作成**（offline 境界監査後に別ステップ）。

---

## Synthetic tests

Fixture: `massive-ticker-events-meta-sanitized.json`（**synthetic / sanitized / not live evidence**）。

カバー例: OBSERVED nonempty / exact SHA / status·results·events 欠落・null・wrong type / empty events notes / event field rejects / invalid calendar date / duplicate preserve / order preserve / unknown fields / next_url / 403·429·500 / transport / invalid UTF-8 / malformed JSON / missing API key / secret 非混入 / canonical requestKey / mismatch refuse / duplicateOf / revisionCandidateOf / eligibility≠event date / identity fields 非生成。

---

## Live validation

| Claim | Status |
| --- | --- |
| Live schema probe | **NOT YET RUN** |
| Live archive validation | **NOT YET RUN** |
| `MASSIVE_API_KEY` used in this revision | **No**（SET でも request 禁止） |

---

## Residual blockers

| Item | Severity |
| --- | --- |
| SecurityId issuance | **Critical**（NO-GO） |
| DailyPrice / Real Backtest | **Critical**（NO-GO） |
| knownAt / PIT | **Critical**（event date / fetch ≠ knownAt） |
| experimental schema / version change | **High** |
| event-inner optionality / empty meaning / completeness | **High**（UNKNOWN） |
| Live schema probe 未実施 | **High** |
| Basic 2-year history | **High**（無料前提制約） |

---

## Next（1 つのみ）

| Option | Select? |
| --- | --- |
| **A. Live Ticker Events schema probe / live archive validation**（max 少数 request；mock 禁止） | **YES（次工程候補）** |
| B. Continuity deriver ↔ Ticker Events corroboration | 後続 |
| C. FIGI consistency PoC | 別軸 |
| D. SecurityId issuance | **禁止** |

選定理由: offline raw/validator 境界は本 PoC で固定。blocking は experimental live shape の実測。

---

## Related docs

- [`massive-ticker-events-gate.md`](massive-ticker-events-gate.md)
- [`security-identity-continuity-evidence-poc.md`](security-identity-continuity-evidence-poc.md)
- [`massive-ticker-overview-forward-archive-poc.md`](massive-ticker-overview-forward-archive-poc.md)
- [`forward-self-archive-design.md`](forward-self-archive-design.md)

---

## Merge advice

- Draft PR としてレビュー可
- **Ready / merge はユーザー指示まで禁止**
- live API を本 PR 作業中に実行しない
- live raw を Git に入れない
- 本文書は SecurityId / continuity GO / knownAt 許可書ではない
