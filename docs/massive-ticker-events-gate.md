# Massive Ticker Events Semantics Gate Review

**Date (UTC):** 2026-09-18  
**Repository:** `siyokugo-rgb/us-stock-investment-assistant`  
**Review type:** Gate Review / semantics audit only（**実装なし** / **live API なし**）  
**Baseline `origin/main` HEAD:** `7dd89b8baccebccfd94cdbc93f7a6df9e6cd8163`  
**Prior MERGED:** PR #42（Real Massive multi-as-of continuity = **LIVE_VERIFIED**）  
**Official primary source:** [Massive REST — Ticker Events](https://massive.com/docs/rest/stocks/corporate-actions/ticker-events)（同 `.md`）

| Gate | Verdict |
| --- | --- |
| Ticker Event raw archive feasibility（境界設計） | **CONDITIONAL PASS** — experimental；archive raw + validator 前提なら可 |
| Ticker-change evidence（単独） | **PARTIAL** — `ticker_change` timeline は候補；OLD/NEW 明示 field なし |
| Ticker-change corroboration + `share_class_figi` | **CONDITIONAL** — Overview 等と組み合わせた候補のみ；確定禁止 |
| Ticker recycle detection | **PARTIAL / UNRESOLVED** — rename/change ≠ recycle 完全検出 |
| Security continuity | **NO-GO** |
| knownAt / PIT | **NO-GO** |
| SecurityIdentifier `validFrom` / `validTo` | **NO-GO** |
| SecurityId issuance | **NO-GO** |
| DailyPrice.currency | **NO-GO** |
| Real Backtest | **NO-GO** |
| Free-tier suitability（endpoint access） | **PASS（公式）** — Stocks Basic Included；履歴は **2 years** |

**本 Gate の中心問題:**

> Massive `GET /vX/reference/tickers/{id}/events` について、ticker change evidence として何を安全に主張でき、何を主張してはならないか？

**本 Gate ではないもの:** Ticker Events client / archive / validator 実装、SecurityId 実装、live schema probe 実行、有料プラン前提化。

---

## Official facts（一次資料のみ）

出典: Massive Docs — Stocks / Corporate Actions / **Ticker Events**。

### Endpoint

| Item | Official |
| --- | --- |
| Path | `GET /vX/reference/tickers/{id}/events` |
| Status | **experimental** |
| Purpose（公式文言） | ticker / CUSIP / Composite FIGI に関連する key events の timeline。ticker changes（symbol renaming / rebranding 等）を highlight |
| Use cases（公式） | Historical reference for ticker symbol changes, data continuity, record-keeping |

### Path / query parameters

| Parameter | Required | Official meaning |
| --- | --- | --- |
| `id` | Yes | Asset identifier: **Ticker**, **CUSIP**, or **Composite FIGI**。case-sensitive ticker 例: `AAPL`。**ticker を与えた場合、その ticker が現在表す entity の events を返す**。過去にその ticker に紐づいていた entity の events を探すには Ticker Details Endpoint で該当 identifier を得よ（公式） |
| `types` | No | comma-separated event types。**現在サポートされる `event_type` は `ticker_change` のみ**。blank なら supported event_types をすべて返す |

### Response Attributes（公式表）

公式 Response Attributes では以下 root / nested root fields は **optional**（本表 Optional 列）。欠落・空・null の **意味**は補完しない（下記 UNKNOWN）。

| Field | Type | Optional | Official description |
| --- | --- | --- | --- |
| `request_id` | string | **Yes** | server-assigned request id |
| `results` | object | **Yes** | requested event data |
| `results.events` | array[object] | **Yes** | event array |
| `results.name` | string | **Yes** | asset name |
| `status` | string | **Yes** | response status |

**KNOWN（schema）:** `results.events` を含む上表 fields は公式 schema 上 **optional**。
**UNKNOWN（意味）:** 欠落の意味 / empty array の意味 / `null` が実際に返るか / no-event を意味するか / entitlement や history cutoff を意味するか / provider completeness — いずれも **推測禁止**。

### Sample response に現れる event object shape（公式 sample）

公式 Response Attributes は event 内 field を表形式で列挙していない。sample に **現れる** keys のみ記録する（推測で拡張しない）:

| Observed in sample | Shape | required / optional / nullable |
| --- | --- | --- |
| `type` | string；sample 値 `"ticker_change"` | **UNKNOWN**（sample observed のみ） |
| `date` | string；sample 値 `YYYY-MM-DD` | **UNKNOWN**（sample observed のみ） |
| `ticker_change` | object | **UNKNOWN**（sample observed のみ） |
| `ticker_change.ticker` | string；sample では各 event に **1 つの ticker**（例: `"META"`, `"FB"`） | **UNKNOWN**（sample observed のみ） |

**公式に存在しない（本 Gate で発明しない）field 名:** `old_ticker` / `new_ticker` / `from` / `to` / `effective_at` / `announced_at` / `known_at` / event `source` / pagination cursors。

### Plan access / recency / history（公式）

| Plan | Access | Recency | History |
| --- | --- | --- | --- |
| Stocks Basic | **Included** | Updated daily | **2 years** |
| Stocks Starter / Developer / Advanced | Included | Updated daily | All history |
| Stocks Business | Included | Updated daily | All history |

Plan History 総注記: Records date back to September 10, 2003（プラン別上限は上表）。

**本プロジェクト前提:** 外部 API **無料利用**。Stocks Basic Free 対象は公式 Included。有料プラン（All history）を前提にしない。

### 本 Gate での live

**不要。** `MASSIVE_API_KEY` 使用なし。schema 実測は次工程候補。

---

## UNKNOWN（公式で確認不能 → 推測禁止）

| Topic | Status |
| --- | --- |
| root `request_id` / `results` / `results.events` / `results.name` / `status` の schema optionality | **KNOWN: optional**（公式 Response Attributes） |
| 上記 optional fields の欠落・empty・`null`・no-event・entitlement/history cutoff・completeness としての **意味** | **UNKNOWN** |
| event 内 `type` / `date` / `ticker_change` / `ticker_change.ticker` の必須 / optional / nullable | **UNKNOWN**（Attributes 表に event 内列挙なし；sample observed のみ） |
| `results.events` 欠落・空配列・`null` の意味 | **UNKNOWN**（schema optional であることとは別） |
| `results.name` 欠落時の扱い | **UNKNOWN** |
| `results` に sample 外 field（例: FIGI/CIK）が公式 Attributes で保証されるか | **UNKNOWN**（Attributes は `events` / `name` のみ） |
| event `date` が effective / announcement / listing / other のいずれかか | **UNKNOWN**（公式は `date` とのみ） |
| events 配列のソート保証（sample は新しい date が先だが契約未記載） | **UNKNOWN** |
| pagination（`next_url` / `limit` / cursor） | **UNKNOWN**（docs に記載なし） |
| event / response の provenance / source field | **UNKNOWN**（Attributes に無し） |
| endpoint completeness（全 rename を必ず返すか） | **UNKNOWN** |
| recycle（別 Security への symbol 再利用）を event chain で表現できるか | **UNKNOWN** |
| `vX` の安定版昇格・breaking change 方針 | **UNKNOWN**（experimental のみ明示） |
| CUSIP / Composite FIGI 入力時の response 差分 | **UNKNOWN**（path 意味のみ公式；差分 schema 未記載） |
| Basic 2-year history と sample（2012 等）の関係の運用詳細 | **UNKNOWN**（プラン表は 2 years；本 Gate は live 未実施） |

---

## 時間意味（最重要・固定）

| Claim | Verdict |
| --- | --- |
| event `date` / 見かけの effective date = `knownAt` | **禁止** |
| fetch time / `fetchedAt` / archive `ingestedAt` = event effective date | **禁止** |
| fetch time = provider historical `knownAt` | **禁止** |
| self-archive で得られるもの | **今回の possession** と **eligibility / availability boundary** のみ |
| 過去時点で「当時知っていた」証拠への遡及 | **禁止** |
| Overview `date=` provider as-of と event `date` の同一視 | **禁止**（別 endpoint・別意味；未証明） |

`evidenceEligibleAt` / `eligibilityBoundaryAt`（既存 continuity / archive モデル）は **evidence availability** であり、identity `validFrom` / `validTo` / `knownAt` ではない（PR #41/#42 維持）。

---

## Identity continuity との接続

Ticker Event **単独**では:

| Claim | Verdict |
| --- | --- |
| SecurityId continuity 確定 | **禁止** |
| ticker change だけで同一 Security 確定 | **禁止** |
| issuer continuity 確定 | **禁止** |

### 組み合わせ（候補規則・実装前固定）

| ID | Pattern | Safe claim | Forbidden |
| --- | --- | --- | --- |
| **A** | Ticker Event が OLD→NEW **候補**（chronology 上 `ticker_change.ticker` が変化）+ 同一 nonblank `share_class_figi`（別 Overview/All Tickers evidence） | `TICKER_CHANGE_CANDIDATE` **corroboration 候補** | SecurityId 発行・continuity 確定 |
| **B** | Event OLD→NEW 候補 + `share_class_figi` conflict | **CONFLICT** 候補 | latest-wins / 自動採用 |
| **C** | Event あり + stable identity 欠損 | event evidence **はある**が Security continuity = **UNRESOLVED** | identity 補完 |
| **D** | Event 無し / 空 | 「change しなかった」**断定禁止**。completeness 未証明なら **absence evidence にしない** | negative proof |
| **E** | CUSIP 入力で event 取得 | CUSIP は lookup key 候補のみ | CUSIP = SecurityId |
| **F** | Composite FIGI 入力 | composite 粒度の lookup | composite を share-class と混同；Composite FIGI = SecurityId |

**OLD→NEW の読み方（公式制約）:**  
明示の `old_ticker` / `new_ticker` field は **公式に無い**。sample は各 event が `ticker_change.ticker`（その `date` 時点の ticker と読める shape）を持つ。chronology からの OLD→NEW **推論は候補に留め**、field 発明・確定主張は禁止。

**ticker 入力の公式注意:** ticker `id` は **現在その ticker が表す entity** の events。過去 entity は Ticker Details で identifier 再取得が必要（公式）。「昔の ticker 文字列を入れれば当時の chain が必ず返る」とは読めない。

---

## Ticker recycle

| Question | Gate answer |
| --- | --- |
| rename/rebrand event は recycle を示すか | **必ずしも示さない**（公式は symbol renaming / rebranding を highlight） |
| event chain が recycle を表現できるか | **UNKNOWN** |
| old/new（推論）だけで別 Security 判定できるか | **不可** |
| missing / empty events | absence ≠ no-recycle；**UNRESOLVED** |
| Ticker recycle handling（evidence） | **PARTIAL / UNRESOLVED** 維持（[`security-identity-ticker-reuse-gate.md`](security-identity-ticker-reuse-gate.md) と整合） |

---

## Experimental endpoint リスク

| Risk | Severity |
| --- | --- |
| schema / field optionality 変更 | **High** |
| `vX` version / path 変化 | **High** |
| availability / plan bundling 変更 | **High** |
| sample 外 field への暗黙依存 | **High**（禁止） |

**固定:** 本番 domain model を experimental raw schema へ **直接依存させない**。  
採用する場合の前提: **archive raw（exact bytes）+ Fail-Closed validator 境界**（documented Attributes / sample-observed keys のみ；UNKNOWN は Fail-Closed または UNRESOLVED）。

---

## Acceptance Matrix

各ケースで 5 軸を **別判定**する。

凡例: TE = ticker-change evidence / SC = Security continuity / KA = knownAt / VB = validity boundary / SI = SecurityId issuance

| # | Scenario | TE | SC | KA | VB | SI |
| --- | --- | --- | --- | --- | --- | --- |
| 1 | ticker OLD→NEW event 候補 + same `share_class_figi` | PARTIAL（corroboration 候補） | **NO-GO**（CANDIDATE まで） | **NO-GO** | **NO-GO** | **NO-GO** |
| 2 | ticker OLD→NEW event 候補 + different `share_class_figi` | PARTIAL | CONFLICT 候補；同一 Security **NO** | **NO-GO** | **NO-GO** | **NO-GO** |
| 3 | ticker event + identity missing | PARTIAL（event のみ） | **UNRESOLVED** | **NO-GO** | **NO-GO** | **NO-GO** |
| 4 | same ticker + no events | **absence≠proof**；TE 断定 **NO** | **UNRESOLVED** | **NO-GO** | **NO-GO** | **NO-GO** |
| 5 | CUSIP input | lookup 可（公式） | CUSIP≠Security；SC **NO-GO** | **NO-GO** | **NO-GO** | **NO-GO** |
| 6 | Composite FIGI input | lookup 可（公式） | composite≠share-class；SC **NO-GO** | **NO-GO** | **NO-GO** | **NO-GO** |
| 7 | duplicate events | raw 保持；自動潰し **禁止** | **UNRESOLVED**/CONFLICT 候補 | **NO-GO** | **NO-GO** | **NO-GO** |
| 8 | conflicting events | CONFLICT 候補 | **NO-GO** | **NO-GO** | **NO-GO** | **NO-GO** |
| 9 | chronology reversed / invalid dates | Fail-Closed / UNRESOLVED | **NO-GO** | **NO-GO** | **NO-GO** | **NO-GO** |
| 10 | event `date` present but knownAt absent | TE 候補可 | **NO-GO** | **absent のまま**；date→knownAt **禁止** | **NO-GO** | **NO-GO** |
| 11 | event fetched years later | possession = fetch 時 | 過去 knownAt **捏造禁止** | **NO-GO** | availability ≠ validity | **NO-GO** |
| 12 | endpoint / schema change | validator 更新まで採用停止 | 旧 raw を黙って新意味へ読替 **禁止** | **NO-GO** | **NO-GO** | **NO-GO** |

---

## Separated verdicts（必須）

| Gate | Verdict | Notes |
| --- | --- | --- |
| Ticker Event raw archive feasibility | **CONDITIONAL PASS** | experimental；raw + validator；domain 直接依存禁止 |
| Ticker-change evidence | **PARTIAL** | `type=ticker_change` + `date` + `ticker_change.ticker`（sample）；明示 OLD/NEW field なし |
| Ticker-change corroboration with `share_class_figi` | **CONDITIONAL** | 組み合わせ A のみ候補；発行・確定なし |
| Ticker recycle detection | **PARTIAL / UNRESOLVED** | rename evidence ≠ recycle detector |
| Security continuity | **NO-GO** | |
| knownAt / PIT | **NO-GO** | event date / fetch ≠ knownAt |
| SecurityIdentifier validFrom/validTo | **NO-GO** | |
| SecurityId issuance | **NO-GO** | |
| DailyPrice.currency | **NO-GO** | |
| Real Backtest | **NO-GO** | |
| Free-tier suitability | **PASS（access） / PARTIAL（history）** | Basic Included；history **2 years**；有料前提禁止 |

---

## Residual blockers

| Item | Severity |
| --- | --- |
| SecurityId 発行ポリシー不在 | **Critical**（意図的 NO-GO） |
| DailyPrice.currency / Real Backtest | **Critical**（NO-GO） |
| knownAt / PIT を event・archive から生成不可 | **Critical** |
| experimental schema / version / availability 変化 | **High** |
| event field optionality / completeness **UNKNOWN** | **High** |
| OLD/NEW 明示 schema 欠如 → chronology 推論依存 | **High** |
| Ticker recycle 確定手順が候補段階 | **High**（Critical は reuse-gate 側の横断問題として維持可） |
| Massive↔OpenFIGI FIGI 粒度整合 | **High**（別軸） |
| Basic 2-year history 限界 | **High**（無料前提の制約；有料で逃げない） |

---

## Next（1 つのみ）

| Option | Select? |
| --- | --- |
| A. Ticker Events forward archive PoC（raw + Fail-Closed validator；SecurityId なし） | **DONE（PR #44）** → [`massive-ticker-events-forward-archive-poc.md`](massive-ticker-events-forward-archive-poc.md) |
| B. Live Ticker Events schema probe / live archive validation | **DONE（PR #45）**（`LIVE_SCHEMA_VERIFIED`；XYZ × 1） |
| C. Ticker Events ↔ Overview share_class corroboration PoC | **DONE（PR #46）** → [`ticker-events-overview-corroboration-poc.md`](ticker-events-overview-corroboration-poc.md) |
| **D. Live corroboration validation**（Overview dated + Events；無料枠） | **YES（次工程候補）** |
| E. SecurityId / Ticker Events client の domain 直結実装 | **禁止** |

**選定理由:** synthetic corroboration は固定済み。blocking は実 archive での CORROBORATED 再現。

---

## Related docs

- [`security-identity-ticker-reuse-gate.md`](security-identity-ticker-reuse-gate.md)
- [`security-identity-continuity-evidence-poc.md`](security-identity-continuity-evidence-poc.md)
- [`security-master-feasibility-poc.md`](security-master-feasibility-poc.md)
- [`massive-ticker-overview-forward-archive-poc.md`](massive-ticker-overview-forward-archive-poc.md)
- [`forward-self-archive-design.md`](forward-self-archive-design.md)

---

## Merge advice

- Draft PR としてレビュー可
- **Ready / merge はユーザー指示まで禁止**
- 本文書は Ticker Events client 実装許可書ではない
- 本文書は SecurityId / SecurityIdentifier / knownAt / validity / DailyPrice / Real Backtest の許可書ではない
- 公式に無い field 名を schema として採用しない
- live raw を Git に入れない；本 Gate は live 未実施
