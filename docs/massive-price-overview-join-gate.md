# Massive PRICE ↔ Massive Ticker Overview Same-Vendor Provenance Join Gate

**Date (UTC):** 2026-09-16  
**Repository:** `siyokugo-rgb/us-stock-investment-assistant`  
**Review type:** Gate Review only（Kotlin / join model / SecurityId / DailyPrice / Backtest 実装なし）  
**Baseline `origin/main` HEAD:** `40471a1754956203ab8c5b0ca12cfc17e9601693`  
**PR #28:** MERGED（`mergedAt=2026-09-16T06:29:24Z`；merge commit = baseline）  
**PR #28 head (ancestor verified):** `76812f99caf4cbf95bc608ae5020722f6104f3f5`

| Gate | Verdict |
| --- | --- |
| Archive-level Massive PRICE ↔ Overview binding | **PASS**（条件付き candidate のみ） |
| Bar-level historical identity attribution | **NO-GO** |
| Trading Currency | **PARTIAL**（evidence candidate；DailyPrice.currency 未解決） |
| Venue | **PARTIAL**（primary_exchange evidence candidate；venue resolved ではない） |
| SecurityId issuance | **NO-GO** |
| DailyPrice | **NO-GO** |
| Complete Security Master | **NO** |
| Forward Research | **CONDITIONAL GO**（維持） |
| Real Backtest | **NO-GO**（維持） |

---

## 0. Baseline / merge confirmation

| Check | Result |
| --- | --- |
| `git fetch` + `checkout main` + `pull --ff-only` | OK |
| local `main` == `origin/main` | OK (`40471a1754956203ab8c5b0ca12cfc17e9601693`) |
| PR #28 state | **MERGED** |
| `76812f99…` is ancestor of `main` | OK |

PR #27（Massive PRICE Custom Bars 1d unadjusted forward raw archive）および PR #28（Massive Ticker Overview forward raw archive）が main に存在する前提で本 Gate を実施する。

---

## 1. Development stage

```text
Forward Price Source Re-selection (Massive CONDITIONAL YES)
  → Massive PRICE raw archive (PR #27)
  → Massive Ticker Overview raw archive (PR #28)
  → 【本レビュー】Massive PRICE ↔ Overview same-vendor provenance join Gate
```

**まだ進まないもの:** join model 実装、SecurityId、DailyPrice、Trading Currency 採用コード、MIC formal model、DB、Backtest、Strategy、Android、All Tickers client、OpenFIGI auto-join、ProviderSymbolBindingEvidence 変更。

---

## 2. What exists on main (facts only)

### 2.1 PRICE archive

| Item | Value |
| --- | --- |
| Domain | `PRICE` |
| Source | `massive.stocks.aggs_1d.unadjusted` |
| Endpoint | `GET /v2/aggs/ticker/{stocksTicker}/range/1/day/{from}/{to}` |
| Query | `adjusted=false&sort=asc&limit={n}&apiKey=…` |
| requestKey | `GET\|/v2/aggs/ticker/{TICKER}/range/1/day/{FROM}/{TO}\|adjusted=false\|sort=asc\|limit={LIMIT}` |
| eligibility | OBSERVED 時 `eligibilityBoundaryAt = ingestedAt` |
| payload | 1 response に複数 day bars（`t` = window start ms）があり得る |

### 2.2 Ticker Overview archive

| Item | Value |
| --- | --- |
| Domain | `SECURITY_MASTER` |
| Source | `massive.stocks.ticker_overview` |
| Endpoint | `GET /v3/reference/tickers/{ticker}` |
| Optional query | `date=YYYY-MM-DD`（provider as-of selector；≠ knownAt / knowledge-PIT） |
| requestKey | `GET\|/v3/reference/tickers/{TICKER}` または `…\|date={YYYY-MM-DD}` |
| eligibility | OBSERVED 時 `eligibilityBoundaryAt = ingestedAt` |
| externalIdentifier | **常に null**（FIGI/CIK/ticker 自動設定なし） |

### 2.3 Shared primitives

`ManifestRecord` / `ManifestStore` / `ImmutableRawStore` / `Sha256Hex` / `CoverageCalculator` / `ObservationStatus` / orphan audit。  
coverage = OBSERVED `ingestedAt` のみ。latest-wins 禁止。historical `knownAt` 非生成。

---

## 3. Official Massive sources（調査日 UTC: 2026-09-16）

公式 docs のみ（非公式二次情報は採用根拠にしない）。

| Topic | Official source | Recorded meaning (as stated) |
| --- | --- | --- |
| Custom Bars | https://massive.com/docs/rest/stocks/aggregates/custom-bars | `stocksTicker` = case-sensitive ticker；`ticker` response = exchange symbol traded under；aggregates from qualifying trades；`t` = Unix ms start of aggregate window；optional `next_url` pagination |
| Ticker Overview | https://massive.com/docs/rest/stocks/tickers/ticker-overview | details for a ticker **active as-of a given date**；delisted → All Tickers `active=false` |
| Overview `date` | same | point in time for ticker info available on that date；SEC filing fields compared to period-of-report date；defaults to most recent available |
| `currency_name` | same | “The name of the currency that this asset is **traded with**.” |
| `primary_exchange` | same | “The **ISO code** of the primary listing exchange for this asset.”（sample: `XNAS`） |
| `composite_figi` | same | “The composite OpenFIGI number for this ticker.” |
| `share_class_figi` | same | “The share Class OpenFIGI number for this ticker.” |
| `active` | same | actively traded；false = delisted |
| `list_date` | same | first publicly listed date `YYYY-MM-DD` |
| `delisted_utc` | same | last date the asset was traded |
| `last_updated_utc` | （field may appear in some responses；Overview attribute table emphasizes list/delisted） | **not treated as knownAt** in this repo（PR #28） |

**Repo contract already fixed (PR #28):** Overview `date` = provider as-of selector；NOT historical knownAt / knowledge-PIT / decision availability / possession time / eligibility backdating.

---

## 4. Non-negotiable principles

```text
same vendor          != safe join
same ticker string   != same Security
same ticker string   != same historical instrument
current Overview     != metadata at each historical bar time
```

| Forbidden automatic elevation | |
| --- | --- |
| ticker match → `SecurityId` | **FORBIDDEN** |
| ticker match → FIGI “確定” | **FORBIDDEN** |
| Overview → historical bar identity | **FORBIDDEN** |
| Overview missing → Security absent | **FORBIDDEN** |
| `active=true` → Security existence absolute | **FORBIDDEN** |
| `currency_name` → `DailyPrice.currency` silent adoption | **FORBIDDEN** |
| `primary_exchange` → venue resolved / SecurityId | **FORBIDDEN** |
| `composite_figi` / `share_class_figi` → SecurityId | **FORBIDDEN** |
| latest PRICE × latest Overview silent join | **FORBIDDEN** |
| past backfill of Overview onto earlier bars | **FORBIDDEN** |

---

## 5. Ticker coincidence meaning

PRICE requestKey と Overview requestKey が **同一 Massive provider ticker 文字列**（case-sensitive canonical parse）で一致する場合に成立を許すのは:

> 「同一 Massive ticker namespace に属する **2 つの forward observations** である」

まで。

**成立しないもの:**

- same SecurityId
- same historical instrument across bar dates
- FIGI continuity across time
- trading currency of each bar as known historically

---

## 6. Document-only concept: `MassivePriceReferenceBindingCandidate`

Production class は **今回作らない**。文書上の最小 candidate:

| Field | Meaning |
| --- | --- |
| `priceArchiveId` | PRICE OBSERVED `archiveId` |
| `overviewArchiveId` | Overview OBSERVED `archiveId` |
| `provider` | `Massive` |
| `providerTicker` | canonical Massive ticker（request provenance only） |
| `priceEligibilityBoundaryAt` | PRICE `eligibilityBoundaryAt` |
| `overviewEligibilityBoundaryAt` | Overview `eligibilityBoundaryAt` |
| `bindingEligibleAt` | `max(price, overview)` eligibility（≠ historical knownAt） |
| `overviewRequestDate` | Overview requestKey の optional `date`（provider as-of；nullable） |
| `currencyName?` | Overview raw `currency_name` if present（evidence only） |
| `primaryExchange?` | Overview raw `primary_exchange` if present |
| `compositeFigi?` | Overview raw `composite_figi` if present |
| `shareClassFigi?` | Overview raw `share_class_figi` if present |
| `active?` | Overview raw `active` if present |
| `status` | e.g. `CANDIDATE` / `CONFLICT` / `INELIGIBLE` |
| `reason?` | Fail-Closed / conflict notes（secret-free） |

---

## 7. Eligibility

両方 OBSERVED かつ `eligibilityBoundaryAt != null` のとき:

```text
bindingEligibleAt = max(priceEligibilityBoundaryAt, overviewEligibilityBoundaryAt)
```

- historical `knownAt` ではない
- past backfill 禁止（`bindingEligibleAt` より前の decision / bar attribution に使わない）
- Overview `date` query や payload `list_date` / `delisted_utc` / `last_updated_utc` で bindingEligibleAt を書き換えない

---

## 8. Archive-level vs bar-level（最重要分離）

### A. Archive-level forward binding — **条件付き可**

PRICE OBSERVED possession と Overview OBSERVED possession を、同一 Massive ticker provenance として **archive 単位**で関連付ける。

言ってよいこと（候補）:

> 「この `bindingEligibleAt` 以降、Massive ticker X の PRICE observation と Massive ticker X の reference observation を同一 provider provenance として関連付けられる。」

### B. Bar-level historical identity attribution — **NO-GO**

PRICE payload は 1 request で複数 market dates（複数 `t`）を含み得る。

例:

| Event | Time |
| --- | --- |
| PRICE archive ingest | 2026-09-16 |
| bars in payload | 2026-08-01 … 2026-09-15 |
| Overview archive ingest | 2026-09-16 |

**禁止:** Overview evidence を各 bar の 2026-08-01 等へ遡及適用して「その日の Security / currency / MIC / FIGI」と主張すること。

Bar-level が必要とするもの（今回未充足）:

- provider identifier / ticker validity history
- delisted/inactive complete master
- FIGI continuity across ticker reuse
- knowledge-PIT（retrospective knownAt）— Massive は ledger を提供しない（既存 Gate 結論）

→ **Bar-level historical identity: NO-GO**

---

## 9. Overview `date` present vs omitted

| Mode | Official meaning | This repo |
| --- | --- | --- |
| `date` omitted | most recent available | latest/provider-current reference possession |
| `date` supplied | provider as-of selector for ticker info available on that date | as-of **evidence selector** only |

どちらも:

- ≠ historical knownAt
- ≠ knowledge-PIT / decision availability
- Overview(`date=2025-01-01`) を 2026 に取得しても、2025-01-01 bar の identity が **当時 known** だったことにはならない

---

## 10. Ticker reuse / symbol change

同一 Massive ticker 文字列が過去 Security A → 現在 Security B に再利用され得る場合:

- **current Overview を old PRICE bars へ適用禁止**
- archive-level candidate も、reuse/ambiguity が疑われる場合は `CONFLICT` / Fail-Closed（自動解決禁止）

必要な evidence（今回実装しない）:

- provider identifier history
- ticker validity windows
- delisted/inactive master completeness
- FIGI continuity

---

## 11. Field semantics & judgments

### 11.1 `currency_name` → Trading Currency

| Layer | Status |
| --- | --- |
| Official raw field | “currency that this asset is traded with” |
| Trading currency **evidence candidate**（same-vendor archive-level binding 後） | **可（条件付き）** |
| `DailyPrice.currency` 採用 / SOLVED | **不可（UNSOLVED 維持；PARTIAL evidence）** |

ticker 一致 alone での採用 **禁止**。bar-level currency attribution **禁止**。

**Trading Currency verdict:** **PARTIAL**

### 11.2 `primary_exchange` → Venue

| Layer | Status |
| --- | --- |
| Official raw field | “ISO code of the primary listing exchange”（sample `XNAS`） |
| Venue / MIC **evidence candidate** | **可（条件付き）** — ISO-code-shaped string as Massive-stated primary listing exchange |
| Venue **resolved** / SecurityId / listing-level Price claim | **不可**（Venue Gate: MIC formal model 未整備；SIP/venue semantics は PRICE 側 PARTIAL） |

**Venue verdict:** **PARTIAL**

### 11.3 FIGI

| Field | Keep as | Must not confuse with |
| --- | --- | --- |
| `composite_figi` | Massive-stated **Composite OpenFIGI** evidence candidate | OpenFIGI venue-level FIGI；SecurityId |
| `share_class_figi` | Massive-stated **Share Class OpenFIGI** evidence candidate | venue-level FIGI；SecurityId |

同一 vendor 内で PRICE ticker ↔ Overview FIGI evidence を **forward reference chain candidate** に置ける（archive-level）。  
FIGI → SecurityId **禁止**。OpenFIGI consistency は **別 Gate**。

### 11.4 `active` / delisted / Overview missing

| Claim | Allowed? |
| --- | --- |
| `active=true` = Security absolute existence | **NO** |
| Overview missing = Security absent | **NO**（not found / unsupported / delisted / inactive / plan/rate limit / other） |
| Ticker Overview alone = complete Security Master | **NO** |
| survivorship-safe master | **NO** |

Complete master 用途の候補: All Tickers + `active=false` 等（**今回 client 追加禁止**）。

---

## 12. Archive-level acceptance minimum

候補作成の最低条件（すべて必須）:

1. PRICE `observationStatus == OBSERVED` かつ `eligibilityBoundaryAt != null`
2. Overview `observationStatus == OBSERVED` かつ `eligibilityBoundaryAt != null`
3. PRICE `source == massive.stocks.aggs_1d.unadjusted`
4. Overview `source == massive.stocks.ticker_overview`
5. both canonical requestKey parse 成功
6. same provider namespace（Massive）
7. exact same canonical provider ticker
8. reference raw validation 成功（Overview OBSERVED 前提）
9. ambiguity / semantic conflict 無し（下記 §14）

**これだけで言えること:** archive-level same-vendor provenance binding candidate。  
**言えないこと:** SecurityId、bar-level identity、DailyPrice、complete master、historical knownAt。

---

## 13. Allowed vs forbidden statements

### Allowed (candidate)

- 「`bindingEligibleAt` 以降、Massive ticker X の PRICE observation と reference observation を同一 provider provenance として関連付けられる」
- 「Overview に `currency_name` / `primary_exchange` / FIGI が raw evidence として存在する（採用は別）」

### Forbidden

- 「この historical bar はこの SecurityId」
- 「この bar の currency は確実に USD」
- 「この bar はこの MIC 上の価格」
- 「過去から同じ FIGI だった」
- 「Overview が無いので Security は存在しない」

---

## 14. Revision / conflict

### Overview revisions（same ticker, different hash）

latest-wins **禁止**。明示 evidence 選択 + forward eligibility。

意味的 conflict 候補（例）:

| Change | Treatment |
| --- | --- |
| `currency_name` 変更 | identity/trading-currency conflict candidate → Fail-Closed until resolved |
| `primary_exchange` 変更 | listing/venue conflict candidate |
| `composite_figi` / `share_class_figi` 変更 | identity conflict candidate |
| `active` true→false | status change；complete delisting claim には不足し得る |
| branding / market_cap / description only | metadata refresh；identity conflict と自動同一視しない |

単なる response metadata 更新と identity 変更を **分離**。疑義は `CONFLICT`。

### PRICE revision × Overview revision

最新×最新の自動 join **禁止**。  
各側の明示 `archiveId` + `bindingEligibleAt` で選択（実装は次工程）。

---

## 15. Acceptance Matrix

| Case | Scenario | Verdict |
| --- | --- | --- |
| 1 | PRICE OBSERVED + Overview OBSERVED + same ticker + both eligibility | **archive-level candidate YES**（conflict 無し前提） |
| 2 | ticker mismatch | **FAIL** |
| 3 | PRICE eligibility 09:00 / Overview 10:00 | `bindingEligibleAt=10:00` |
| 4 | Overview later than historical bars in PRICE payload | archive-level candidate **可**；bar-level attribution **禁止** |
| 5 | Overview current only + old PRICE bars | past backfill **FORBIDDEN** |
| 6 | Overview `date` supplied | provider as-of evidence candidate；≠ historical knownAt |
| 7 | `currency_name` present | Trading Currency **evidence candidate**（PARTIAL）；DailyPrice 採用なし |
| 8 | `primary_exchange` present | Venue **evidence candidate**（PARTIAL）；venue resolved なし |
| 9 | `composite_figi` present | Composite OpenFIGI evidence candidate；≠ SecurityId |
| 10 | same ticker / FIGI changed across Overview revisions | **CONFLICT / UNRESOLVED**（auto-pick 禁止） |
| 11 | Overview missing | Security absent **判定禁止** |
| 12 | `active=false` / delisted evidence incomplete | complete master claim **禁止** |

---

## 16. Mandatory answers (A–H)

| # | Question | Answer |
| --- | --- | --- |
| A | same-vendor ticker 一致だけで archive-level join candidate を作れるか | **YES（条件付き）** — §12 最低条件 + conflict 無し。SecurityId ではない |
| B | bar-level identity join は可能か | **NO**（NO-GO） |
| C | `currency_name` は Trading Currency evidence として採用可能か | **PARTIAL YES as evidence candidate**（same-vendor binding 後）。DailyPrice.currency SOLVED ではない |
| D | `primary_exchange` は venue evidence として採用可能か | **PARTIAL YES as evidence candidate**（ISO-code-shaped primary listing exchange per Massive）。venue resolved ではない |
| E | composite/share_class FIGI をどの粒度で保持可能か | Massive-stated **Composite / Share Class OpenFIGI evidence candidates**；venue-level OpenFIGI と混同禁止；SecurityId 禁止 |
| F | current Overview を historical PRICE へ適用可能か | archive-level binding **のみ**（`bindingEligibleAt` 以降）。bar-level / past backfill **不可** |
| G | Ticker Overview 単独で complete Security Master になるか | **NO** |
| H | 次に実装すべき最小作業 | **A. Massive same-vendor binding evidence 最小実装** |

---

## 17. Separated verdicts

| Gate | Verdict |
| --- | --- |
| Archive-level Massive PRICE↔Overview binding | **PASS**（条件付き candidate） |
| Bar-level historical identity | **NO-GO** |
| Trading Currency | **PARTIAL** |
| Venue | **PARTIAL** |
| SecurityId issuance | **NO-GO** |
| DailyPrice | **NO-GO** |
| Forward Research | **CONDITIONAL GO** |
| Real Backtest | **NO-GO** |

---

## 18. Residual blockers

| Item | Severity |
| --- | --- |
| historical knownAt / CA PIT / Universe | **Critical**（Real Backtest） |
| Trading Currency → DailyPrice.currency 採用未完 | **Critical**（DailyPrice）；本 Gate で evidence path は PARTIAL まで |
| Bar-level identity / ticker reuse history | **Critical**（historical attribution） |
| Same-vendor binding evidence 未実装 | **High**（次工程） |
| Ticker Overview 単独の inactive/delisted completeness 不足 | **High** |
| SIP/venue semantics（PRICE endpoint） | **High**（既存 PARTIAL） |
| OpenFIGI ↔ Massive FIGI consistency | **High**（別 Gate） |
| SecurityId / MIC formal model | **High / NO-GO** |

---

## 19. Next（1 つのみ）

候補から選定:

| Option | Select? |
| --- | --- |
| **A. Massive same-vendor binding evidence 最小実装** | **YES** |
| B. Trading Currency Evidence PoC | 後続（binding 無しでは provenance 欠落） |
| C. All Tickers inactive/delisted archive PoC | 必要だが complete master 軸；本 join blocker の最短ではない |
| D. MIC/Venue evidence PoC | binding 後 |
| E. FIGI consistency Gate | 別軸 |
| F. SecurityId Issuance Gate | 時期尚早 |

**選定理由:** archive-level PASS を実体化する最小実装が、currency / venue evidence を Fail-Closed で運ぶ前提条件。Kotlin join model は evidence 記録に限定し、SecurityId / DailyPrice / bar-level attribution は開かない。

---

## 20. Related docs

- [`massive-price-forward-archive-poc.md`](massive-price-forward-archive-poc.md)
- [`massive-ticker-overview-forward-archive-poc.md`](massive-ticker-overview-forward-archive-poc.md)
- [`forward-price-source-reselection-gate.md`](forward-price-source-reselection-gate.md)
- [`venue-listing-identity-gate-review.md`](venue-listing-identity-gate-review.md)
- [`forward-security-price-join-gate-review.md`](forward-security-price-join-gate-review.md)
- [`forward-self-archive-design.md`](forward-self-archive-design.md)
- [`security-master-acceptance-criteria.md`](security-master-acceptance-criteria.md)

---

## 21. Merge advice

- Draft PR としてレビュー可
- **main 自動 merge しない**
- 本文書は SecurityId / DailyPrice / Real Backtest / bar-level identity の許可書ではない
- Archive-level binding PASS ≠ Trading Currency SOLVED ≠ Venue SOLVED
