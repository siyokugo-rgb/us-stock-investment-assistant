# Massive Ticker Overview Forward Archive PoC

**Date (UTC):** 2026-09-16  
**Baseline SHA:** `8756ba2cb6ad4c95f766f9e104cbfe08365246a0` (`origin/main` verified)  
**PR #27:** MERGED（ancestor `68b4d891…` verified）  
**Contract SoT:** [`forward-self-archive-design.md`](forward-self-archive-design.md)、[`massive-price-forward-archive-poc.md`](massive-price-forward-archive-poc.md)  
**Scope:** 1 domain × 1 source raw archive boundary only（PRICE↔Overview join は開かない）

| Gate | Status |
| --- | --- |
| Massive Ticker Overview Forward Raw Archive Boundary | **PASS（本 PoC）** |
| PRICE ↔ Ticker Overview same-vendor join | **NO-GO（今回未実装）** |
| Trading Currency / Venue / MIC / SecurityId / DailyPrice | **NO-GO** |
| Forward Research | **CONDITIONAL GO**（維持） |
| Real Backtest | **NO-GO**（維持） |

---

## Purpose

Massive Stocks **Ticker Overview** の HTTP response exact bytes を、forward-only **security/reference metadata** evidence として保存する。

PRICE aggregate archive（`massive.stocks.aggs_1d.unadjusted`）とは **別 source**。

**目的ではないもの:** currency / MIC / FIGI / SecurityId / DailyPrice への採用、PRICE↔Overview join。

用語:

| Term | Meaning |
| --- | --- |
| transport raw | HTTP から完全受信した response body exact bytes |
| raw evidence | レスポンスに存在する field の possession（≠ 内部 domain 採用） |

---

## Endpoint（公式確認済み）

| Item | Value |
| --- | --- |
| Provider | Massive |
| Docs | https://massive.com/docs/rest/stocks/tickers/ticker-overview |
| Method/Path | `GET /v3/reference/tickers/{ticker}` |
| Base | `https://api.massive.com` |
| Optional query | `date=YYYY-MM-DD`（省略時 = latest available） |
| Auth | env `MASSIVE_API_KEY` → query `apiKey`（PRICE client と同境界） |

### `date` query semantics（PIT / knownAt 分離）

Massive 公式: `date` は ticker information の **provider as-of selector**（その日付時点で利用可能な reference 詳細を返す）。SEC filing 由来 field については period-of-report date との比較例が docs にある。

**禁止:** `date` を次のいずれにも解釈しない。

- historical `knownAt`
- knowledge-PIT / decision availability
- possession time の代理
- `eligibilityBoundaryAt` への遡及根拠

`eligibilityBoundaryAt` は **OBSERVED 成功時の `ingestedAt` のみ**（request `date` や payload の `list_date` / `delisted_utc` / `last_updated_utc` ではない）。

### Delisted / inactive / complete Security Master

公式 Ticker Overview: **active as-of date** の single ticker details。  
Delisted tickers については docs が **All Tickers + `active=false`** を案内する。  
All Tickers forward archive PoC: [`massive-all-tickers-forward-archive-poc.md`](massive-all-tickers-forward-archive-poc.md)（complete master ではない）。

したがって:

| Claim | 本 PoC |
| --- | --- |
| Ticker Overview forward raw archive boundary | PASS（本 PoC スコープ） |
| Complete Security Master | **NO** |
| Delisted / inactive universe completeness | **NO** |
| Survivorship-safe master | **NO** |

### Pagination

公式 Ticker Overview は **single-object** `results`（array / `next_url` ではない）。  
pagination framework は追加しない。万一 root に string `next_url` が存在すれば PR #27 同様 **Fail-Closed REJECTED_VALIDATION**（follow しない）。

---

## Domain / Source

| Item | Value |
| --- | --- |
| Domain | `SECURITY_MASTER`（OpenFIGI `openfigi.v3.mapping` と同 domain；別 source） |
| Source | `massive.stocks.ticker_overview` |

既存 source は改名しない。PRICE source とも分離。

---

## Archive layout

```text
{archiveRoot}/
  SECURITY_MASTER/
    massive.stocks.ticker_overview/
      manifest.jsonl
      raw/
        {archiveId}.raw
```

Reuses: `ManifestRecord` / `ManifestStore` / `ImmutableRawStore` / `Sha256Hex` / `CoverageCalculator` / `ObservationStatus` / `TransportStatus` / orphan audit。

---

## API key

- env `MASSIVE_API_KEY` only
- requestKey / notes / fixtures / raw path / manifest / exception message へ混入禁止
- **未設定:** HTTP attempt 前 fail-fast（`IllegalStateException`）。provider failure possession **非生成**（PR #27 境界）
- live helper: key 無し → `LIVE_UNVERIFIED`（mock 禁止）

---

## requestKey（secret-free）

```text
GET|/v3/reference/tickers/{TICKER}
GET|/v3/reference/tickers/{TICKER}|date={YYYY-MM-DD}
```

- API key 禁止
- ticker は request provenance のみ（≠ SecurityId）
- `archivePossessedResponse` は possession.requestKey 一致を Fail-Closed

---

## Timestamps

| Field | Meaning |
| --- | --- |
| `attemptedAt` | request 開始 UTC |
| `attemptFinishedAt` | request 終了 UTC |
| `fetchedAt` | response body **完全受信**時のみ（HTTP status 非依存） |
| `ingestedAt` | archive 確定側時刻 |
| `eligibilityBoundaryAt` | OBSERVED 成功時のみ `= ingestedAt` |

`list_date` / `delisted_utc` / `last_updated_utc` → knownAt / validFrom / validTo / eligibility **変換禁止**。  
Request query `date` も同様に eligibility / knownAt へ **変換禁止**。

---

## Status matrix

| Case | Status | fetchedAt | raw | eligibility |
| --- | --- | --- | --- | --- |
| HTTP 2xx + UTF-8 + status=OK + results object + ticker match + type checks | `OBSERVED` | yes | yes | = ingestedAt |
| HTTP 2xx but validation fail | `REJECTED_VALIDATION` | yes | yes | null |
| complete 4xx/5xx body | `PROVIDER_FAILURE` | yes | yes | null |
| transport / request-construction failure | `PROVIDER_FAILURE` | null | null | null |
| raw/manifest local failure | `LOCAL_ARCHIVE_FAILURE` | maybe | maybe | null |
| `MASSIVE_API_KEY` missing | fail-fast；no provider record | — | — | — |

Transport failure notes = exception **class simple name only**。

---

## Validation（OBSERVED 最低条件）

- HTTP 2xx
- strict UTF-8（`CodingErrorAction.REPORT`）
- valid JSON
- `status == "OK"`
- no string `next_url`
- `results` **object** 存在
- `results.ticker` nonblank かつ request ticker 一致（case-sensitive）
- optional: `active` boolean；`primary_exchange` / `currency_name` / `composite_figi` / `share_class_figi` / `cik` は string なら nonblank；`list_date` は YYYY-MM-DD；`delisted_utc` / `last_updated_utc` は timestamp-ish string（形式確認のみ）
- local raw write + manifest append 成功

Validation 成功 ≠ domain 採用。

---

## Field semantics（分離）

| Raw field | 本 PoC の意味 | 禁止される昇格 |
| --- | --- | --- |
| `currency_name` | Massive reference evidence | `DailyPrice.currency` |
| `primary_exchange` | Massive reference evidence | venue resolved / MIC / SecurityId |
| `composite_figi` | external id evidence **candidate** | SecurityId / externalIdentifier 自動設定 |
| `share_class_figi` | share-class evidence candidate | SecurityId |
| `cik` | string evidence only | IssuerId 自動生成 |
| `ticker` | request/response provenance | SecurityId |
| `list_date` / `delisted_utc` / `last_updated_utc` | raw timestamps | knownAt / eligibility / validFrom/To |

`ManifestRecord.externalIdentifier` / `externalIdentifierNamespace` は **常に null**（自動設定しない）。

---

## Duplicate / revision / coverage / orphan

- same requestKey + same hash → `duplicateOf`
- same requestKey + different hash → `revisionCandidateOf`（≠ correction；latest-wins 禁止）
- coverage = OBSERVED `ingestedAt` のみ（`list_date` 等は使わない）
- orphan raw audit only（自動削除/補完/eligibility 付与禁止）

---

## Live smoke

```text
./gradlew --no-daemon -q massiveTickerOverviewForwardArchivePoc
```

`MASSIVE_API_KEY` 無ければ **LIVE_UNVERIFIED**。live raw は git commit 禁止。

---

## Tests

`MassiveTickerOverviewForwardArchivePocTest`（synthetic）:

- OBSERVED + exact SHA-256
- whitespace hash 差
- malformed JSON / invalid UTF-8
- provider error envelope
- 403 / 429 / 500
- transport failure
- API key missing fail-fast
- request construction secret 非流出
- requestKey canonical / apiKey 非混入
- ticker mismatch / wrong type / blank primary_exchange
- duplicate / revision
- coverage OBSERVED only / orphan
- currency/MIC/FIGI/CIK/SecurityId/DailyPrice/knownAt 非生成
- next_url Fail-Closed
- **PIT:** request `date=2024-06-01` + ingest `2026-09-16…` → `eligibilityBoundaryAt == ingestedAt`（payload dates に遡及しない）

既存 Massive PRICE / AV / OpenFIGI / binding regression は full `test` で維持。

---

## Residual blockers

| Item | Severity |
| --- | --- |
| historical knownAt / CA PIT / Universe | Critical（Real Backtest） |
| Trading currency 採用（raw evidence ≠ SOLVED） | Critical（DailyPrice） |
| PRICE ↔ Overview same-vendor join Gate | High（次工程） |
| Ticker Overview 単独の inactive/delisted completeness 不足 | High |
| SecurityId / venue / MIC formal model | High / NO-GO |
| OpenFIGI ↔ Massive FIGI consistency | High（別 Gate） |

---

## Next

1. **Massive PRICE ↔ Massive Ticker Overview** 同一 vendor provenance join Gate — 文書: [`massive-price-overview-join-gate.md`](massive-price-overview-join-gate.md)  
2. そこで ticker consistency / reference eligibility / currency_name evidence / primary_exchange evidence / FIGI evidence の安全な結線を判断  
3. 本 PoC では join を実装しない

### Join Gate 制約（先取り明記）

次 Gate では **Fail-Closed**:

- Overview が見つからない → **Security 不存在と判断しない**（delisted/inactive coverage 不足の可能性）
- delisted/inactive completeness 不足を黙認しない
- **current active ticker 向け forward candidate** と **complete Security Master** を分離して扱う

---

## Merge advice

- Draft PR としてレビュー可
- **main 自動 merge しない**
- 本 PoC PASS ≠ Trading Currency / Venue / SecurityId / DailyPrice GO
