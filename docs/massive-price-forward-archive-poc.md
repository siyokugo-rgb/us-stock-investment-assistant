# Massive Forward PRICE Raw Archive PoC (Custom Bars 1d unadjusted)

**Date (UTC):** 2026-09-16  
**Baseline SHA:** `d6facb7f07dac14c11864786cee97c3f0e59e2b3` (`origin/main` verified)  
**PR #26:** MERGED（ancestor `15bf92a9…` verified）  
**Contract SoT:** [`forward-self-archive-design.md`](forward-self-archive-design.md)、[`forward-price-source-reselection-gate.md`](forward-price-source-reselection-gate.md)  
**Scope:** 1 domain × 1 source raw archive boundary only

| Gate | Status |
| --- | --- |
| Massive Forward PRICE Raw Archive Boundary | **PASS（本 PoC）** |
| DailyPrice / SecurityId / Trading Currency / MIC / FIGI join | **NO-GO（本 PoC では開かない）** |
| Forward Research | **CONDITIONAL GO**（維持） |
| Real Backtest | **NO-GO**（維持） |

---

## Purpose

Massive Stocks Custom Bars から取得した **HTTP response exact bytes** を、forward-only possession evidence として保存する。

**目的ではないもの:** Massive PRICE を DailyPrice に変換して「使えるようにする」こと。

用語:

| Term | Meaning |
| --- | --- |
| transport raw | HTTP から完全受信した response body exact bytes |
| unadjusted aggregate | Custom Bars `adjusted=false`（split 未調整の **aggregate**） |

`adjusted=false` ≠ raw tape / raw exchange trade。  
Custom Bars は qualifying trades から生成された aggregate（公式 docs）。

---

## Source

| Item | Value |
| --- | --- |
| Provider | Massive |
| Domain | `PRICE` |
| Source | `massive.stocks.aggs_1d.unadjusted` |
| Endpoint | `GET https://api.massive.com/v2/aggs/ticker/{stocksTicker}/range/1/day/{from}/{to}` |
| Query | `adjusted=false&sort=asc&limit={explicit}&apiKey=…` |
| Auth | env `MASSIVE_API_KEY` only（query `apiKey`；公式 quickstart） |

### Explicitly out of scope

Ticker Overview、`currency_name` / `primary_exchange` / FIGI join、OpenFIGI join、SecurityId、DailyPrice、trading currency、MIC、Composite/Share Class FIGI 採用、CA、split/dividend 補正、`adjusted=true`、historical knownAt、Backtest、Strategy、DB、Android、**AV archive 削除/置換**。

---

## Archive layout

```text
{archiveRoot}/
  PRICE/
    massive.stocks.aggs_1d.unadjusted/
      manifest.jsonl
      raw/
        {archiveId}.raw
```

Reuses: `ManifestRecord` / `ManifestStore` / `ImmutableRawStore` / `Sha256Hex` / `CoverageCalculator` / `ObservationStatus` / `TransportStatus` / orphan audit。

---

## requestKey（secret-free）

Bound on `MassiveHttpPossession.requestKey` at attempt time（HTTP 前）。

```text
GET|/v2/aggs/ticker/{TICKER}/range/1/day/{FROM}/{TO}|adjusted=false|sort=asc|limit={LIMIT}
```

- API key 禁止
- ticker は request provenance のみ（≠ SecurityId）
- `archivePossessedResponse` は possession.requestKey と client.requestKeyFor の一致を Fail-Closed

---

## Timestamps

| Field | Meaning |
| --- | --- |
| `attemptedAt` | request 開始 UTC |
| `attemptFinishedAt` | request 終了 UTC |
| `fetchedAt` | response body **完全受信**時のみ（HTTP status 非依存） |
| `ingestedAt` | archive 確定側時刻 |
| `eligibilityBoundaryAt` | OBSERVED 成功時のみ `= ingestedAt` |

`fetchedAt` / `ingestedAt` ≠ historical knownAt。  
Massive `t` = aggregate **window start** ms → knownAt / publication / DailyPrice.tradingDate への変換 **禁止**。

---

## Status matrix

| Case | Status | fetchedAt | raw | eligibility |
| --- | --- | --- | --- | --- |
| HTTP 2xx + UTF-8 + status=OK + adjusted=false + ticker match + **no string `next_url`** + ≥1 valid results | `OBSERVED` | yes | yes | = ingestedAt |
| HTTP 2xx but validation fail（`next_url` string / adjusted=true / empty / OHLCV / …） | `REJECTED_VALIDATION` | yes | yes | null |
| complete 4xx/5xx body | `PROVIDER_FAILURE` | yes | yes | null |
| transport / request-construction failure | `PROVIDER_FAILURE` | null | null | null |
| raw/manifest local failure | `LOCAL_ARCHIVE_FAILURE` | maybe | maybe | null |
| `MASSIVE_API_KEY` missing（local configuration） | **no possession / no provider failure record** — fail-fast `IllegalStateException` before HTTP | — | — | — |

Transport failure notes = exception **class simple name only**（`e.message` / full URL / apiKey 禁止）。

Local configuration（API key 未設定）は provider HTTP attempt 前に fail-fast。provider failure possession を生成しない。live helper は key 無し → `LIVE_UNVERIFIED`（mock 禁止）。

---

## Validation（OBSERVED 最低条件）

- HTTP 2xx
- strict UTF-8（`CodingErrorAction.REPORT`）
- valid JSON
- `status == "OK"`
- `ticker` == request ticker（case-sensitive）
- `adjusted == false`
- **`next_url` が string として存在しない**（null / JSON null のみ許容）
- `results` array 存在かつ ≥1
- optional `resultsCount` 整合
- row: `o,h,l,c,v,t` finite；negative price/volume reject；high≥low；O/C in range；`t` positive integer；strict ascending；no duplicate `t`
- optional `n` / `vw` があれば非負 finite

### Pagination（Fail-Closed）

公式 Custom Bars response の optional `next_url` が **string**（空文字含む）として root に存在する場合:

- `REJECTED_VALIDATION`
- `eligibilityBoundaryAt = null`
- OBSERVED 禁止（first page を requested range の完全 coverage とみなさない）
- raw bytes / SHA-256 は immutable 保存
- **`next_url` を follow しない**（PoC は pagination 追跡を実装しない）
- `next_url` を requestKey に入れない / 値を notes・log に出さない（secret 有無を仮定しない）
- 空文字 `next_url` の provider 意味は推測せず Fail-Closed

Validation 成功 ≠ DailyPrice 生成。

---

## Duplicate / revision / coverage / orphan

既存 AV/OpenFIGI 契約を再利用:

- same requestKey + same hash → `duplicateOf`
- same requestKey + different hash → `revisionCandidateOf`（≠ correction confirmed）
- coverage = OBSERVED `ingestedAt` のみ（payload market date ではない）
- orphan raw audit only（自動削除/補完/eligibility 付与禁止）

---

## Live smoke

任意。`MASSIVE_API_KEY` が無ければ **LIVE_UNVERIFIED**（mock 禁止・key 生成禁止）。

```text
./gradlew --no-daemon -q massiveDailyAggsForwardArchivePoc
```

Default 取得窓（live smoke 専用；DailyPrice.tradingDate / market calendar には使わない）:

| Env | Default |
| --- | --- |
| `MASSIVE_TO` | UTC `today.minusDays(2)` |
| `MASSIVE_FROM` | UTC `today.minusDays(14)` |

固定年月日は使わない（将来腐るため）。`MASSIVE_FROM` / `MASSIVE_TO` で override 可。  
Basic 制約（5 calls/min、2y history、EOD）を超えない。live raw は git commit 禁止。

---

## Tests

Synthetic coverage（`MassiveDailyAggsForwardArchivePocTest`）:

- OBSERVED + exact SHA-256
- whitespace hash 差
- adjusted=true reject
- ticker mismatch / missing / empty results
- malformed JSON / invalid UTF-8（exact raw 保持）
- negative OHLC/volume、high&lt;low、O/C range、duplicate/malformed/non-asc `t`
- **pagination: no `next_url` → OBSERVED；`next_url` present → REJECTED + raw/hash + coverage 0**
- blank `next_url` Fail-Closed reject
- duplicate / revision 契約維持（pagination reject 後も）
- 403 / 429 / 500 PROVIDER_FAILURE
- transport failure（no fetchedAt/hash；class name only）
- request construction secret 非流出（malformed baseUrl 含む）
- **API key missing → fail-fast；provider failure possession 非生成**
- requestKey apiKey 非混入
- **dynamic live window `from < to` / recent relative to todayUtc**
- coverage OBSERVED only
- orphan audit
- currency / MIC / FIGI / SecurityId / DailyPrice 非生成
- SIP/venue claim 非昇格
- possession requestKey mismatch refuse

既存 AV / OpenFIGI / binding regression も full `test` で維持。

---

## Residual blockers

| Item | Severity |
| --- | --- |
| historical knownAt / CA PIT / Universe | Critical（Real Backtest） |
| Trading currency（Ticker Overview path 未取得） | Critical（DailyPrice） |
| SIP/venue semantics endpoint 明示（Gate PARTIAL） | High |
| SecurityId / DailyPrice mapping | High / NO-GO |
| MIC / FIGI join（今回未取得） | High（次候補） |

---

## Next

1. Massive Ticker Overview forward archive（currency_name / primary_exchange / FIGI）— **別 source**
2. 同一 vendor 内の price↔reference provenance join Gate（ticker alone 禁止）
3. AV archive は維持

---

## Merge advice

- Draft PR としてレビュー可
- **main 自動 merge しない**
- 本 PoC PASS ≠ DailyPrice / Real Backtest GO
