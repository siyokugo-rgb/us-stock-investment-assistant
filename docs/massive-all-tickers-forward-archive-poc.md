# Massive All Tickers Forward Archive PoC

**Date (UTC):** 2026-09-16  
**Baseline `origin/main` HEAD:** `46cc6b1c3940702e5497ee4238e9834a411e5d89`  
**PR #31:** MERGED（`7879e84b…` ancestor verified；merge commit = baseline parent chain）  
**Contract SoT:** [`forward-self-archive-design.md`](forward-self-archive-design.md)、[`massive-ticker-overview-forward-archive-poc.md`](massive-ticker-overview-forward-archive-poc.md)、[`trading-currency-evidence-gate.md`](trading-currency-evidence-gate.md)  
**Scope:** 1 domain × 1 source raw archive boundary only（DailyPrice.currency / SecurityId / Trading Currency SOLVED / complete master は開かない）

| Gate | Status |
| --- | --- |
| Massive All Tickers Forward Raw Archive Boundary | **PASS（本 PoC）** |
| Trading Currency Evidence | **NOT SOLVED**（raw `currency_symbol` 保存のみ） |
| DailyPrice.currency | **NO-GO** |
| SecurityId | **NO-GO** |
| Venue | **PARTIAL**（変更なし） |
| Complete Security Master | **NO** |
| Forward Research | **CONDITIONAL GO**（維持） |
| Real Backtest | **NO-GO**（維持） |

---

## Purpose

Massive Stocks **All Tickers**（`GET /v3/reference/tickers`）の HTTP response exact bytes を、forward-only **security/reference metadata** evidence として保存する。

最重要: Ticker Overview では得られなかった **`currency_symbol`（公式: ISO 4217 code of the currency that this asset is traded with）** を raw evidence として保持する。

**目的ではないもの:** `DailyPrice.currency` 昇格、ISO normalize、MIC/SecurityId、complete inactive universe、pagination follow、TradingCurrencyEvidence class。

---

## Endpoint（公式確認済み・調査日 UTC 2026-09-16）

| Item | Value |
| --- | --- |
| Provider | Massive |
| Docs | https://massive.com/docs/rest/stocks/tickers/all-tickers |
| Method/Path | `GET /v3/reference/tickers` |
| Base | `https://api.massive.com` |
| Auth | env `MASSIVE_API_KEY` → query `apiKey` |

### PoC request filters

| Query | PoC |
| --- | --- |
| `market=stocks` | **常に付与**（requestKey 必須） |
| `ticker=` | optional exact filter |
| `active=` | optional (`true`/`false`)；inactive/delisted 経路 |
| `date=` | optional YYYY-MM-DD provider as-of selector |
| `limit` | explicit（default 1 for single-page PoC；max 1000） |
| `sort` / `order` | used by default (`ticker` / `asc`) → requestKey に含める |

### `currency_symbol` vs `currency_name`

| Field | Official meaning |
| --- | --- |
| `currency_name` | “The name of the currency that this asset is **traded with**.” |
| `currency_symbol` | “The **ISO 4217 code** of the currency that this asset is traded with.” |

Overview は `currency_name` のみ。All Tickers が ISO path の公式根拠。  
本 PoC: **provider raw value 保持のみ**。uppercase normalize / `DailyPrice.currency` 生成 **禁止**。

**明示:** OBSERVED raw `currency_symbol` possession ≠ validated ISO 4217 domain value。  
fixture / test で lowercase `"usd"` を残しても、それは raw 非正規化の例であり「valid ISO code として採択した」意味ではない。

### `active` request filter ↔ response 整合

`requestedActive != null` のとき Fail-Closed:

| Request | Response row `active` | Outcome |
| --- | --- | --- |
| `active=false` | `true` | REJECTED_VALIDATION |
| `active=true` | `false` | REJECTED_VALIDATION |
| explicit filter | missing / null | REJECTED_VALIDATION（request から row state を推測しない） |
| `active=false` | `false` | OBSERVED（他条件満たす場合） |
| omit (`null`) | true/false/missing | 既存 optional evidence 契約（型検査のみ） |

mismatch / missing でも raw/hash は immutable 保持。eligibility=null。coverage に含めない。

### `active=false` / delisted

公式: `active=false` で inactive/delisted 取得経路がある。`delisted_utc` は “The last date that the asset was traded.”

| Claim | 本 PoC |
| --- | --- |
| inactive observation raw archive | PASS（filter + **response active 整合** + field 検証） |
| Complete Security Master / delisted universe completeness | **NO** |
| Survivorship-safe master | **NO** |

### Pagination

公式: `next_url` で次ページ。  
本 PoC: **follow しない**。string `next_url` 存在 → **REJECTED_VALIDATION**（raw/hash 保持、eligibility=null）。  
**first page ≠ complete coverage**。

### `date` / PIT

`date` = provider as-of selector（その日に利用可能な tickers）。  
**禁止:** `date` / `delisted_utc` / `last_updated_utc` → `knownAt` / eligibility / coverage。

`eligibilityBoundaryAt` = **OBSERVED 成功時の `ingestedAt` のみ**。

---

## Domain / Source

| Item | Value |
| --- | --- |
| Domain | `SECURITY_MASTER` |
| Source | `massive.stocks.all_tickers` |

Ticker Overview（`massive.stocks.ticker_overview`）および PRICE（`massive.stocks.aggs_1d.unadjusted`）とは **分離**。既存 source は改名しない。

---

## Archive layout

```text
{archiveRoot}/
  SECURITY_MASTER/
    massive.stocks.all_tickers/
      manifest.jsonl
      raw/
        {archiveId}.raw
```

---

## requestKey

Secret-free canonical form:

```text
GET|/v3/reference/tickers|market=stocks[|ticker=…][|active=…][|date=…]|limit=N[|sort=…][|order=…]
```

- API key **禁止**
- filter 意味が違う request は別 key（例: `active` omit ≠ `active=true`）
- `next_url` / cursor は requestKey に入れない

---

## Success validation（Fail-Closed）

最低限:

- HTTP 2xx
- `status=OK`
- `results` array（object ではない）
- strict UTF-8（`CodingErrorAction.REPORT`）
- valid JSON
- non-empty `results`（empty → REJECTED_VALIDATION；**not MISSING** — MISSING は body 無し契約）
- `next_url` 無し
- ticker filter 指定時: 全 `results[].ticker` 一致
- `market=stocks` 契約: present なら `results[].market=stocks`
- **`active` filter 指定時:** 全 row の `active` boolean が request と一致；欠損も REJECTED
- field 型検査: `active` boolean；`currency_*` / FIGI / exchange / timestamps string；blank string reject
- raw immutable + manifest append 成功

---

## Empty results 方針

| Semantics | Status |
| --- | --- |
| HTTP 200 + complete body + `results=[]` | **REJECTED_VALIDATION** |
| MISSING（design: no body / no hash） | **使わない** |

PRICE empty-results と同経路。provider は body を返しているため MISSING ではない。

---

## Duplicate / revision

| Pattern | Treatment |
| --- | --- |
| same requestKey + same hash | `duplicateOf` |
| same requestKey + different hash | `revisionCandidateOf`（≠ authoritative correction） |
| different filters/date | 別 requestKey |

latest-wins **禁止**。

---

## Coverage

`coverageStartAt` / `coverageThroughAt` = **OBSERVED `ingestedAt` only**。  
provider `date` / `last_updated_utc` / `delisted_utc` は coverage に使わない。

---

## No promotion

自動採用 **禁止**:

- `currency_symbol` → `DailyPrice.currency`
- `currency_name` → `DailyPrice.currency`
- `primary_exchange` → MIC / venue resolved
- `composite_figi` / `share_class_figi` → SecurityId
- `cik` → IssuerId
- `ticker` → SecurityId
- `ManifestRecord.externalIdentifier` 自動設定

---

## Tests（synthetic）

`MassiveAllTickersForwardArchivePocTest` — 最低限:

1. valid single-result → OBSERVED  
2. exact raw SHA-256  
3. malformed JSON → REJECTED  
4. invalid UTF-8 → REJECTED + raw保持  
5. 403/429/500 → PROVIDER_FAILURE  
6. transport failure  
7. API key missing fail-fast  
8. secret 非流出  
9. ticker mismatch  
10. wrong `currency_symbol` type  
11. blank `currency_symbol`  
12. non-canonical `currency_symbol` raw 保持（≠ ISO domain adoption）  
13. `active=false` + response `active=false` → OBSERVED  
14. `active=false` + response `active=true` → REJECTED  
15. `active=true` + response `active=false` → REJECTED  
16. explicit `active` + missing field → REJECTED  
17. `active` omit → optional evidence 維持  
18. date query ≠ eligibility backdate  
19. `next_url` → REJECTED  
20. first page coverage 禁止  
21. empty results → REJECTED not MISSING  
22. duplicate / revisionCandidate  
23. coverage OBSERVED only  
24–27. DailyPrice.currency / SecurityId / knownAt / MIC 生成なし  
28–30. PRICE / Overview / binding regression（既存 suite）

Live: `./gradlew massiveAllTickersForwardArchivePoc`（key 無し → LIVE_UNVERIFIED）。

---

## Residual blockers

| Item | Severity |
| --- | --- |
| historical knownAt / CA PIT / Universe | **Critical** |
| `DailyPrice.currency` adoption | **Critical** |
| Bar-level currency / ticker reuse history | **Critical** |
| Pagination complete coverage | **High** |
| Complete inactive/delisted universe | **High** |
| MIC / Venue formal resolution | **High** |
| OpenFIGI ↔ Massive FIGI consistency | **High** |
| Currency Evidence Gate（Overview+AllTickers+PRICE） | **High**（次工程） |

---

## Next

本 PoC PASS 後:

**Currency Evidence Gate** — 必須コア PRICE + All Tickers（Overview = optional corroboration）:  
[`massive-currency-evidence-gate.md`](massive-currency-evidence-gate.md)

TradingCurrencyEvidence class はその Gate 後に必要性を再評価。本 PoC では実装しない。

---

## Merge advice

- Draft PR としてレビュー可  
- **main 自動 merge しない**  
- PASS ≠ Trading Currency SOLVED ≠ DailyPrice.currency GO ≠ Complete Security Master  
