# Massive Ticker Events Forward Archive PoC

**Date (UTC):** 2026-09-18
**Baseline `origin/main` HEAD（archive PoC）:** `fb19b2a968f233317a0f015fd5c09128dfde7c54`
**Live probe base main:** `f4ba15f85b99fe995c5af94af07f7570becc683a`（PR #44 MERGED）
**PR #45:** Draft（live schema probe）
**PR #44:** MERGED（offline archive）
**Semantics Gate:** [`massive-ticker-events-gate.md`](massive-ticker-events-gate.md)（PR #43 MERGED）
**Contract SoT:** [`forward-self-archive-design.md`](forward-self-archive-design.md)
**Mode:** synthetic PASS + live schema probe **LIVE_SCHEMA_VERIFIED**（SecurityId / knownAt / continuity GO ではない）

| Gate | Status |
| --- | --- |
| Ticker Events forward raw archive boundary（synthetic） | **PASS（PR #44）** |
| Live schema probe / live archive validation | **LIVE_SCHEMA_VERIFIED**（XYZ × 1） |
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

- Live runner: `MassiveTickerEventsLiveArchivePoc` / Gradle `massiveTickerEventsForwardArchivePoc`
- Default lookup id: `XYZ`（1 request）
- Live validation: **LIVE_SCHEMA_VERIFIED**（下記）

---

## Synthetic tests

Fixture: `massive-ticker-events-meta-sanitized.json`（**synthetic / sanitized / not live evidence**）。

カバー例: OBSERVED nonempty / exact SHA / status·results·events 欠落・null・wrong type / empty events notes / event field rejects / invalid calendar date / duplicate preserve / order preserve / unknown fields / next_url / 403·429·500 / transport / invalid UTF-8 / malformed JSON / missing API key / secret 非混入 / canonical requestKey / mismatch refuse / duplicateOf / revisionCandidateOf / eligibility≠event date / identity fields 非生成。

---

## Live validation

| Claim | Status |
| --- | --- |
| Live schema probe | **LIVE_SCHEMA_VERIFIED**（下記 Latest live attempt） |
| Live archive validation | **PASS（1 request / XYZ）** |
| Continuity deriver connection | **NO**（未接続） |
| SecurityId / knownAt / OLD→NEW invention | **NO-GO 維持** |

### Latest live attempt

| Field | Value |
| --- | --- |
| executedAt (UTC) | `2026-09-18T07:50:58Z`（fetchedAt / ingestedAt 近傍） |
| `MASSIVE_API_KEY` | **SET**（値は記録しない） |
| lookup id | `XYZ`（opaque；≠ SecurityId） |
| request count | **1**（retry なし；control ticker なし） |
| requestKey | `GET\|/vX/reference/tickers/XYZ/events\|types=ticker_change` |
| httpStatus | `200` |
| observationStatus | `OBSERVED` |
| observedIngestSucceeded | `true` |
| eventCount | `2` |
| validatedEvents（raw order） | `[0] type=ticker_change date=2025-01-21 ticker=XYZ`；`[1] type=ticker_change date=2015-11-18 ticker=SQ` |
| liveClassification | **LIVE_SCHEMA_VERIFIED** |
| rawPayloadHash | `19898be5cdf5dd24dcd0a1c0c921d2360ad57e870eacc0c1664f6d6ae35bce70` |
| SHA-256 re-verify | **MATCH**（disk bytes == manifest hash） |
| eligibilityBoundaryAt | `2026-09-18T07:50:58.867024101Z`（`ingestedAt`；event date へ backdate なし） |
| provider failure / 403 / 429 | **なし** |
| live raw / manifest | **Git 未収録**（`archive-runtime` gitignored） |
| root keys observed | `request_id`, `results`, `status` |
| results keys observed | `cik`, `composite_figi`, `events`, `name`（`cik`/`composite_figi` は sample 外；business 未使用） |

**意味境界（維持）:**

- event `date` ≠ `knownAt` / `validFrom` / `validTo`
- lookup id ≠ SecurityId；`ticker_change.ticker` ≠ SecurityId
- OLD→NEW 非発明；raw 順保存；sort/dedupe なし
- continuity / SecurityId issuance **なし**
- 外部資料上の SQ→XYZ effective（2025-01-21）と Massive `date` が一致しても、`date`→`knownAt` 転用 **禁止**

---

## Residual blockers

| Item | Severity |
| --- | --- |
| SecurityId issuance | **Critical**（NO-GO） |
| DailyPrice / Real Backtest | **Critical**（NO-GO） |
| knownAt / PIT | **Critical**（event date / fetch ≠ knownAt） |
| experimental schema / version change | **High** |
| event-inner optionality / empty meaning / completeness | **High**（UNKNOWN；live 1 件で解消しない） |
| Continuity deriver ↔ Ticker Events corroboration | **High**（未接続） |
| Basic 2-year history vs older events 出現 | **High**（観測事実；プラン意味の断定禁止） |

---

## Next（1 つのみ）

| Option | Select? |
| --- | --- |
| A. Live Ticker Events schema probe / live archive validation | **DONE（PR #45）**（`LIVE_SCHEMA_VERIFIED`；XYZ × 1 request） |
| B. Ticker Events ↔ Overview share_class corroboration PoC（TICKER_CHANGE_CANDIDATE；SecurityId なし） | **DONE（PR #46）** → [`ticker-events-overview-corroboration-poc.md`](ticker-events-overview-corroboration-poc.md) |
| **C. Live corroboration validation**（Overview dated + Events；無料枠） | **YES（次工程候補）** |
| D. SecurityId issuance | **禁止** |

選定理由: synthetic corroboration は固定済み。blocking は実 archive での CORROBORATED 再現。

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
