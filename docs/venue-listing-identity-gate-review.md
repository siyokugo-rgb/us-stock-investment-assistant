# Venue / Listing Identity Gate Review

**Date (UTC):** 2026-09-16  
**Investigation date (UTC):** 2026-09-16  
**Repository:** `siyokugo-rgb/us-stock-investment-assistant`  
**Review type:** Gate Review only（Kotlin / VenueEvidence / MIC mapper / SecurityId / DailyPrice / Backtest 実装なし）  
**Baseline `origin/main` HEAD:** `aac42b84dd8577f1c33ac5394fb3ade0256e2620`  
**PR #23:** MERGED（`mergedAt=2026-09-16T03:59:19Z`）  
**PR #23 head (ancestor verified):** `f79089cf0fcff2540b3ead0d2ae69cd95076ccfe`

| Gate | Verdict |
| --- | --- |
| ProviderSymbolBindingEvidence | **PARTIAL**（symbol→FIGI candidate まで。Security / listing resolved ではない） |
| Venue Identity | **FAIL / PARTIAL**（exchCode alone 不足；MIC 未保持） |
| Listing Identity | **PARTIAL**（venue-level FIGI は listing 候補になり得るが、AV daily との粒度整合・MIC・validity 未充足） |
| Share Class Identity | **PARTIAL**（`shareClassFIGI` は補助証拠；正式モデルなし） |
| SecurityId issuance | **NO-GO** |
| Trading Currency | **UNSOLVED**（本 Gate の identity 条件とは分離） |
| DailyPrice mapping | **NO-GO** |
| Forward Research | **CONDITIONAL GO**（維持） |
| Real Backtest | **NO-GO**（維持） |

関連 Gate Review: [`forward-security-price-join-gate-review.md`](forward-security-price-join-gate-review.md)

---

## 0. Baseline / merge confirmation

| Check | Result |
| --- | --- |
| `git fetch` + `checkout main` + `pull --ff-only` | OK |
| local `main` == `origin/main` | OK (`aac42b84…`) |
| PR #23 state | **MERGED** |
| `f79089c…` is ancestor of `main` | OK |

---

## 1. Development stage

```text
Forward Self-Archive
  → Security Master raw archive (OpenFIGI)
  → Price raw archive (Alpha Vantage TIME_SERIES_DAILY)
  → OpenFIGI request provenance
  → ProviderSymbolBindingEvidence (CANDIDATE / AMBIGUOUS / INELIGIBLE)
  → 【本レビュー】Venue / Listing Identity Gate Review
```

**まだ進まないもの:** SecurityId 発行、PriceSecurityJoinCandidate、DailyPrice、Trading Currency implementation、DB、Repository、Backtest、Strategy、Portfolio、Android UI、broker 連携、VenueEvidence / MIC client 実装。

---

## 2. What exists on main (facts only)

### 2.1 ProviderSymbolBindingEvidence（PR #23）

純導出モデル。`derive(priceRecord, mappingRecord)` のみ。caller 自由 `providerSymbol` API なし。

CANDIDATE 最低条件（現行コード）:

- PRICE / OpenFIGI とも `OBSERVED` + non-null eligibility
- PRICE canonical `requestKey` から symbol を strict 導出
- OpenFIGI request provenance（hash / uri / requestKey 整合 + exact request raw）
- request job = 1、`idType=TICKER`、`idValue == providerSymbol`
- response revalidate で一意 `figi`、manifest external id と一致、`namespace=figi`
- `bindingEligibleAt = max(price, mapping)` eligibility（historical knownAt ではない）

保持し得る補助:

- `mappingExchCode?`（**request input** 由来。MIC ではない）
- `externalIdentifier` / `namespace=figi`（manifest 一意時のみ）

**明示的に持たないもの:** MIC、`micCode` request provenance、`compositeFIGI` / `shareClassFIGI` の evidence フィールド、listing validity、ticker history、SecurityId、currency。

### 2.2 OpenFIGI archive

- response: `figi`, `compositeFIGI`, `shareClassFIGI`, `ticker`, `exchCode`, `securityType`, `marketSector`, `name` 等を raw に保持
- request: exact bytes + `requestPayloadHash`；`parseJobs` で `idType` / `idValue` / optional `exchCode`
- **現行 request encode は `exchCode` のみ。`micCode` は未送信・未保存**

### 2.3 Domain identity（未結合）

`SecurityId` / `IssuerId` / `SecurityIdentifier` / `SecurityIdentifierIndex` / `IssuerSecurityRelation` は存在するが、archive binding から SecurityId への発行経路は無い。Exchange / MIC / listing / share class の正式モデルは未実装（Acceptance Criteria が evidence 要件のみ定義）。

---

## 3. Official sources (investigation date: 2026-09-16)

一次情報のみ（非公式ブログは根拠にしない）:

| Source | Role |
| --- | --- |
| [OpenFIGI API Documentation](https://www.openfigi.com/api/documentation) | Mapping request/response fields；`exchCode` vs `micCode`（mutually exclusive） |
| [OpenFIGI Allocation Rules PDF](https://www.openfigi.com/docs/figi-allocation-rules.pdf) | FIGI / Composite / Share Class 割当粒度 |
| [OMG FIGI v1.2](https://www.omg.org/spec/FIGI/1.2/PDF) | FIGI 標準：Global / Composite / Share Class hierarchy |
| [ANSI X9.145-2021 FIGI](https://x9.org/wp-content/uploads/2021/08/ANSI-X9.145-2021-Financial-Instrument-Global-Identifier-FIGI.pdf) | US national FIGI standard（hierarchy 同趣旨） |
| [ISO 10383 FAQ (ISO 20022 / SWIFT RA)](https://www.iso20022.org/sites/default/files/media/file/FAQ_ISO_10383_January2023.pdf) | Operating MIC vs Segment MIC |
| ISO 10383:2012（Securities — Codes for exchanges and market identification） | MIC の目的・operating / segment 定義 |

---

## 4. Identifier granularity separation (must not conflate)

| Layer | Meaning | Current main evidence |
| --- | --- | --- |
| A. Issuer | 発行体 | `IssuerId` 型のみ。本 binding 経路では未解決 |
| B. Share class | 同一 issuer 内の株式クラス | OpenFIGI `shareClassFIGI`（raw 補助）。正式モデルなし |
| C. Instrument / security | 取引可能な証券 identity | 内部 `SecurityId`（未発行）。venue-level FIGI は候補になり得るが自動発行禁止 |
| D. Listing | 特定市場での上場・表示単位 | venue-level FIGI（公式: equity は per instrument per trading venue） |
| E. Trading venue | 取引所 / platform | ISO MIC（未保持）。OpenFIGI `exchCode` は **別体系** |
| F. Provider symbol | AV request provenance | PRICE `requestKey` の `symbol=` のみ |

**禁止維持:** IssuerId で Security を潰す / ticker 文字列 alone / FIGI がある＝SecurityId 解決済み。

---

## 5. Official meanings

### 5.1 FIGI（venue-level / instrument-level）

公式（Allocation Rules §1.4.1 / Open Symbology）:

- 全 asset class に割当され、一度発行されると不変
- **Equity:** 「identifier is issued **per instrument per trading venue**」
- 例: IBM Common Stock の **NYSE venue-level** 割当が説明例として出る

→ Equity の response `figi` は **listing / trading-venue 粒度の instrument id 候補**であり、内部 `SecurityId` ではない。  
→ 「unique FIGI = listing resolved + SecurityId」は **不可**（validity / class / price 粒度 / MIC 整合が別途必要）。

### 5.2 Composite FIGI

公式:

- 同一 country / market 内の **複数 trading-venue FIGI を束ねる** aggregated view
- OpenFIGI Mapping `idType=COMPOSITE_ID_BB_GLOBAL` / response `compositeFIGI`

→ **国・市場集約ビュー**。個別 listing（特定 exchange）の一意証明ではない。  
→ AV の US consolidated daily と「国レベル集約」は概念的に近いが、**自動同一視は禁止**（別途 semantics 契約が必要）。

### 5.3 Share Class FIGI

公式:

- Equities / Funds に割当
- 複数 country の Composite FIGI を **同一 instrument class として全球集約**
- Allocation Rules 例: IBM の US / LN / GR 等 Composite が同一 Share Class FIGI を共有

→ **share-class 粒度の横断リンク**。Listing 一意でも SecurityId でもない。  
→ GOOG / GOOGL のような class 差は別 Share Class FIGI になり得る → **同一 Security への潰し禁止**（Acceptance §10 と一致）。

### 5.4 OpenFIGI `exchCode`

公式 API docs:

- Mapping Job の **optional filter**
- 「Exchange code of the desired instrument(s)」
- **`micCode` と同時使用不可**
- 値は OpenFIGI enum（`/v3/mapping/values/exchCode`）

Allocation Rules の Taiwan 特例:

- 同一 `exchCode=TT` が Taipei SE / Taiwan SE の両方に使われた legacy
- ticker + exchCode だけでは exchange を一意にできないケースが公式に記載

→ `exchCode` は **OpenFIGI 独自の exchange/market code**であり、**ISO MIC ではない**。  
→ **`exchCode → MIC` 無根拠変換は FORBIDDEN**。  
→ `"US"` のような値は **country / composite market** 側に寄ることが多く、特定 listing venue（XNYS / XNAS 等）の確定証拠にならない。

### 5.5 ISO MIC（Operating / Segment）

公式 ISO 10383 / SWIFT FAQ:

- MIC: exchange / trading platform / market / trade reporting facility の識別
- **Operating MIC:** 親（entity operating the market）
- **Segment MIC:** 特定 instrument / 規制差のある区画；親 Operating MIC に紐付く
- 形式はどちらも 4 文字（差はメタデータの OPRT/SGMT）

OpenFIGI:

- Mapping Job に **`micCode`（ISO MIC）** filter が公式存在
- 現行 PoC は未使用

→ Venue identity の標準コードは MIC。  
→ Listing 確定には、少なくとも **どの MIC で問い合わせ・観測したか**の provenance が必要（Operating 必須、Segment は dark pool 等で必要なとき MUST）。

### 5.6 Provider symbol（Alpha Vantage）

- PRICE archive の request provenance のみ
- SecurityId / FIGI / MIC / currency ではない
- TIME_SERIES_DAILY は **raw daily series**（本 repo 契約上 currency unresolved；US equity は多くの場合 consolidated 日次として消費されるが、**venue-specific listing 宣言ではない**）

---

## 6. What CANDIDATE currently proves vs does not prove

| Claim | Status |
| --- | --- |
| AV provider symbol を OpenFIGI に `TICKER` で問い合わせた | **可**（exact request provenance） |
| その問い合わせに対し一意 `figi` が返った | **可**（response revalidate + manifest 一致） |
| その FIGI が「正しい SecurityId」 | **不可** |
| その FIGI が特定 exchange listing として確定 | **条件付き不可**（venue-level FIGI でも MIC 未証明；`exchCode=US` は複合市場） |
| share class が一意に分離済み | **不可**（`shareClassFIGI` を evidence モデル化していない） |
| listing validity / ticker history | **不可** |
| DailyPrice に使える | **不可**（SecurityId + currency 未解決） |

**要約:** CANDIDATE = **provider symbol ↔ FIGI binding candidate provenance**。  
**≠** listing resolved / venue resolved / Security identity resolved。

---

## 7. Listing identity — minimum evidence

「この provider symbol がどの listing を指すか」を forward-only で安全に言うための **最低条件（文書）**:

| Evidence | Role |
| --- | --- |
| ProviderSymbolBindingEvidence = CANDIDATE | symbol↔FIGI request/response provenance |
| Venue-level FIGI（equity） | listing/instrument candidate id |
| **Explicit venue code with known system** | OpenFIGI **`micCode`（ISO MIC）** が推奨；`exchCode` alone は不足し得る |
| Optional: response `exchCode` / `securityType` | 補助照合（推測禁止） |
| Forward eligibility（`bindingEligibleAt`） | いつからその mapping を知っていたか |
| Ambiguity = 0 | multi-FIGI / conflicting FIGI は Fail-Closed |

**まだ MUST ではないが SecurityId 前に必要になり得るもの:**

- `shareClassFIGI`（複数 class があり得る issuer）
- listing / ticker validity（retrospective・長期 forward）
- price series の listing vs composite semantics 契約

**Alpha Vantage TIME_SERIES_DAILY 特記:**  
US consolidated daily を扱うなら、**単一 exchange listing FIGI への無理な固定は誤り**になり得る。国レベル Composite / `exchCode=US` の **composite market view** と price semantics を明示契約しない限り、listing resolved と宣言してはならない。

---

## 8. Venue identity — minimum evidence

| Candidate approach | Forward binding | Listing resolved | SecurityId | DailyPrice |
| --- | --- | --- | --- | --- |
| A. OpenFIGI `exchCode` only | 補助 | **不足**（MIC 非等価；複合 code / Taiwan legacy） | NO-GO | NO-GO |
| B. FIGI + `exchCode` | PARTIAL | PARTIAL（venue-level FIGI があっても MIC 未証明） | NO-GO | NO-GO |
| C. FIGI + **explicit MIC**（request `micCode` provenance） | 前進 | **候補 GO**（ambiguity 0 + eligibility） | なお class/validity 不足なら NO-GO | NO-GO |
| D. FIGI + MIC + shareClass evidence | 前進 | 前進 | **issuance 候補検討可**（なお history/currency は別） | NO-GO（currency） |
| E. Provider-specific listing id | 将来 | provider 契約次第 | 契約次第 | 契約次第 |

**最低 venue identity:** ISO MIC（少なくとも Operating MIC）。Segment MIC はセグメント差が価格・規制に影響するとき MUST。  
**現行 main:** MIC 未保持 → Venue Identity = **FAIL〜PARTIAL**。

---

## 9. Share class — minimum evidence

| Need | Current | Verdict |
| --- | --- | --- |
| 複数 class の分離 | `shareClassFIGI` が raw にあり得るが未モデル化 | **PARTIAL** |
| class alone → SecurityId | 禁止 | **FORBIDDEN** |
| Issuer で潰す | 禁止 | **FORBIDDEN** |

SecurityId issuance 前に「複数 class があり得る issuer」では share-class distinction **MUST**（Acceptance §10）。単一 class が provenance 上証明できるまで Fail-Closed。

---

## 10. Validity / eligibility / knownAt（混同禁止）

| Concept | Meaning | Current |
| --- | --- | --- |
| A. `eligibilityBoundaryAt` / `bindingEligibleAt` | evidence を forward で使い始めた境界 | あり（archive ingest） |
| B. listing / ticker `validFrom`–`validTo` | その identifier が市場で有効だった期間 | **OpenFIGI current mapping では未証明** |
| C. historical `knownAt` | 過去時点で知り得た時刻 | **UNRESOLVED / UNUSABLE** |

**Forward-only:** current OpenFIGI mapping は **`bindingEligibleAt` 以降**のみ利用可。  
**Forbidden:** current mapping を古い PRICE 行へ past backfill。  
**Ticker reuse/change:** history source 無しでは retrospective 不可。長期 forward でも ticker recycle リスクが残る → High。

---

## 11. Dual listing / ADR / share class scenarios

| Scenario | Effect |
| --- | --- |
| Same share class / multiple venues | venue-level FIGI が分かれ得る；MIC 無しで listing 一意と断定しない |
| Same issuer / different shareClassFIGI | 別 Security candidate；潰し禁止 |
| Ordinary vs ADR / ADS | 別 instrument / 別 FIGI になり得る；`securityType` は補助のみ；Issuer 統合禁止 |
| Same symbol string / multiple FIGI | 既に AMBIGUOUS（現行 deriver） |
| Same FIGI / repeated observations | conflict にしない（PR #23 整合） |

---

## 12. Acceptance matrix

| Case | Inputs | Listing / Venue verdict |
| --- | --- | --- |
| 1 | unique FIGI only；venue evidence なし | Listing **候補のみ**。resolved **NO**。SecurityId **NO-GO** |
| 2 | unique FIGI + `exchCode` | PARTIAL。`exchCode=US` 等は MIC 非等価 → resolved **NO**（厳格） |
| 3 | unique FIGI + explicit MIC（request provenance） | Listing/venue **候補前進**。ambiguity 0 なら listing candidate **条件付き可**。SecurityId は class/validity 次第でなお NO-GO |
| 4 | FIGI + MIC + shareClass evidence | SecurityId **issuance 検討候補**（currency は別）。DailyPrice なお NO-GO |
| 5 | same symbol / multiple FIGI | **AMBIGUOUS** |
| 6 | same FIGI / repeated observations | **conflict にしない**（CANDIDATE 維持可） |
| 7 | same issuer / different shareClassFIGI | **separate Security candidate** |
| 8 | same share class / multiple venues | **listing ambiguity** unless MIC/venue-level FIGI で一意化 |
| 9 | current mapping only / old price | **past backfill FORBIDDEN** |
| 10 | venue evidence が price より後 | eligibility は **後側**（`max`） |
| 11 | `exchCode` だけで MIC 推測 | **FORBIDDEN**（公式 mapping evidence 無し） |
| 12 | timezone / US symbol から venue 推測 | **FORBIDDEN** |

---

## 13. SecurityId issuance boundary（文書のみ）

将来発行に必要な identity evidence（Trading Currency を含めない）:

- stable external id + namespace（例: venue-level FIGI）
- instrument / listing 粒度の明示
- share class 分離（必要時 MUST）
- venue（MIC）明示（dual-list / listing 一意性が必要なとき MUST）
- ambiguity = 0
- provenance（request/response）
- forward eligibility
- validity / ticker history（retrospective MUST；長期 forward は High risk）

**Trading currency は SecurityId identity 条件から分離**（DailyPrice blocker）。

---

## 14. DailyPrice boundary（文書のみ）

Venue / listing 解決 ≠ DailyPrice GO。

DailyPrice 追加 MUST（既存型と一致）:

- resolved `SecurityId`
- explicit **trading** currency
- OHLCV + raw/adjusted 境界
- eligibility /（Backtest なら）usable `knownAt`

---

## 15. OpenFIGI alone sufficiency

| Need | OpenFIGI alone | Verdict |
| --- | --- | --- |
| Security identity (internal SecurityId) | 外部 id 候補のみ | **FAIL**（発行層未整備） |
| Listing identity | venue-level FIGI + optional micCode | **PARTIAL** |
| Venue identity | `micCode` filter は公式対応；現行未使用。`exchCode` alone 不足 | **PARTIAL / FAIL（現状）** |
| Share class | `shareClassFIGI` あり | **PARTIAL** |
| Validity / ticker history | current mapping 中心 | **FAIL**（history） |
| Forward Research aid | binding + raw archives | **PASS（CONDITIONAL）** |

**結論:** OpenFIGI は listing/venue の **重要な一次 source**だが、**単独で SecurityId / DailyPrice / Real Backtest を開かない**。MIC request provenance と share-class / validity の扱いを足さない限り listing resolved 宣言は不可。

---

## 16. Additional sources needed（現在不足しているものだけ）

| Missing evidence | Candidate future source type | Implement now? |
| --- | --- | --- |
| ISO MIC as first-class venue id | OpenFIGI `micCode` filter + ISO 10383 official MIC list（SWIFT RA） | **No**（本 PR は文書のみ） |
| exchCode↔MIC 対応を主張する場合 | OpenFIGI / Bloomberg 公式対応表（無ければ主張禁止） | No |
| ticker / listing validity history | commercial / exchange master / vendor history | No（forward-only では後回し可） |
| trading currency | 別 Gate（Critical for DailyPrice） | No |

---

## 17. Document-only evidence status vocabulary（最小）

実装しない。将来 deriver 用の最小語彙:

| Status | When |
| --- | --- |
| `LISTING_CANDIDATE` | CANDIDATE binding + venue-level FIGI + explicit MIC provenance + ambiguity 0 |
| `AMBIGUOUS_VENUE` | multi venue / multi FIGI / conflicting MIC |
| `MISSING_VENUE` | MIC 無し（`exchCode` only 含む） |
| `MISSING_SHARE_CLASS` | 複数 class が疑われ shareClass evidence 無し |
| `VALIDITY_UNRESOLVED` | listing/ticker validity 期間が無い（forward 限定利用） |

巨大 enum 禁止。`RESOLVED_SECURITY` は作らない。

---

## 18. Severity re-evaluation

| Item | Previous | Now | Reason |
| --- | --- | --- | --- |
| Trading currency | Critical（DailyPrice） | **Critical（維持）** | identity と分離；DailyPrice blocker |
| Historical knownAt / CA PIT / Universe | Critical | **Critical（維持）** | Real Backtest |
| Venue / MIC | High | **High（維持）** ※listing resolved / dual-list では実質 blocker | 公式に `exchCode≠MIC`；現行未保持。Forward Research 自体は CONDITIONAL 維持のため Critical へ上げない |
| Share class | High | **High（維持）** | SecurityId 前 MUST になり得る |
| Ticker history | High | **High（維持）** | forward-only で緩和、recycle 残存 |
| Provider symbol↔FIGI binding provenance | Critical（旧） | **緩和済み（PR #22/#23）** | CANDIDATE まで成立；Security resolved ではない |

---

## 19. Required answers

| # | Question | Answer |
| --- | --- | --- |
| A | unique FIGI だけで listing identity は確定可能か | **いいえ**（候補にはなるが、venue/MIC・price 粒度・validity・ambiguity 処理なしに resolved 宣言不可） |
| B | OpenFIGI `exchCode` だけで venue は確定可能か | **いいえ**（MIC 非等価；複合 code / Taiwan legacy） |
| C | MIC は必要か | **listing/venue resolved を主張するなら YES**（少なくとも Operating MIC） |
| D | Operating / Segment のどちらが必要か | **Operating MIC を基本 MUST**。Segment 差が価格・規制に効く場合 Segment MIC も MUST |
| E | shareClassFIGI は SecurityId 発行に必須か | **複数 class があり得る場合 MUST**。単一 class 証明時は必須と断定しないが、潰し禁止 |
| F | forward-only で ticker history 無しでもどこまで安全か | **bindingEligibleAt 以降の research のみ**。past backfill / Backtest / recycle-proof は不可 |
| G | OpenFIGI 単独で十分か | **listing/venue は PARTIAL；SecurityId/DailyPrice/Backtest は不十分** |
| H | 次の最小作業 | **MIC mapping evidence PoC**（下記 §20） |

---

## 20. Next minimal work（1つ）

### 選択: **B. MIC mapping evidence PoC**

理由:

- 公式 OpenFIGI が `micCode`（ISO MIC）filter を既に定義し、`exchCode` と相互排他
- 現行 CANDIDATE の最大 listing/venue ギャップは **MIC 未保持 / exchCode 過信**
- SecurityId / DailyPrice / currency 実装より小さく、Venue Identity PARTIAL→前進に直結

**含めない:** SecurityId 発行、DailyPrice、Trading Currency implementation、exchCode→MIC 推測表の勝手な固定、Android。

**十分になった後の次点:** ShareClassEvidence または SecurityId Issuance Gate（currency は別）。

---

## 21. ¥10,000 economics（技術と分離）

| Lens | Note |
| --- | --- |
| Technical | MIC / richer master が必要でも技術要件として記録 |
| Economic | 有料 exchange/security master は口座 1 万円では **ECONOMICALLY_UNSUITABLE** になり得る |
| Rule | 「高いから技術 FAIL」としない。OpenFIGI `micCode` は無料 API 範囲で試せる点が有利 |

---

## 22. Reviewer verdicts

| Role | Verdict | Note |
| --- | --- | --- |
| SE | **PASS (review)** | 粒度分離・禁止経路・次最小作業が明確 |
| Programmer | **PASS (no code)** | Kotlin 変更なし |
| Data Integrity | **PASS** | CANDIDATE≠Security resolved；exchCode≠MIC；backfill 禁止維持 |
| QA | **PASS** | main 実装（PR #23）と矛盾なし；Acceptance / Join Gate と整合 |

---

## 23. Merge advice for this review PR

- Draft PR としてレビュー可
- **main へ自動 merge しない**
- 本文書は SecurityId / DailyPrice / Real Backtest の実装許可書ではない

---

## 24. References

- [`forward-security-price-join-gate-review.md`](forward-security-price-join-gate-review.md)
- [`security-master-acceptance-criteria.md`](security-master-acceptance-criteria.md)
- [`openfigi-forward-archive-poc.md`](openfigi-forward-archive-poc.md)
- [`price-forward-archive-poc.md`](price-forward-archive-poc.md)
- [`data-contract.md`](data-contract.md)
- [`feasibility-gate-review.md`](feasibility-gate-review.md)
- Official sources in §3
