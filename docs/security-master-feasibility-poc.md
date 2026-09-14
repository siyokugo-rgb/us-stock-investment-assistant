# Security Master Feasibility PoC

**調査日 (UTC):** 2026-09-14  
**Repository:** `siyokugo-rgb/us-stock-investment-assistant`  
**Source of Truth:** GitHub `main`  
**Baseline SHA:** `14514a1875b2fd605ca6d3bfc6d59f1ba4dff0d2`（照合済・一致）  
**採点基準 SoT:** [`security-master-acceptance-criteria.md`](security-master-acceptance-criteria.md)  
**Scope:** 調査・文書化のみ。Provider client / model / mapper / DB / Backtest / Strategy / Android は対象外。  
**契約購入・有料申込・API key取得:** なし（公開一次資料ベース）。  
**Real Backtest:** **NO-GO**（本 PoC は自動 GO にしない）。

本文書は Acceptance Criteria に対する **候補 source の採点**である。  
特定 vendor の本番採用承認ではない。

### 維持する不変条件

| 規則 | 維持 |
| --- | --- |
| ticker → `SecurityId` 禁止 | 維持 |
| CIK → `SecurityId` 禁止 | 維持 |
| external id == `SecurityId` 禁止 | 維持 |
| current identifier の過去逆適用禁止 | 維持 |
| ambiguity 自動解決禁止 | 維持 |
| Issuer:Security = 1:N | 維持 |
| currency 推測禁止 | 維持 |
| `knownAt` / `ingestedAt` / validity 分離 | 維持 |
| delisted 欠如を survivorship 上無視しない | 維持 |
| share class 自動統合禁止 | 維持 |
| latest-wins 禁止 | 維持 |

### 現行コード監査（変更なし）

監査対象: `SecurityId`, `SecurityIdentifier`, `SecurityIdentifierIndex`, `IdentifierType`, `IssuerId`, `IssuerIdentifier`, `IssuerSecurityRelation`, `IssuerSecurityRelationIndex`, `DailyPrice`、および関連 docs。

**Critical なコード矛盾:** 新規発見なし（修正なし）。

関連: [`price-source-entitlement-review.md`](price-source-entitlement-review.md)、[`data-contract.md`](data-contract.md) §1 / §2.1.1、[`feasibility-gate-review.md`](feasibility-gate-review.md)、[`corporate-action-split-poc.md`](corporate-action-split-poc.md)。

---

## 1. 評価方法

| ラベル | 意味 |
| --- | --- |
| **PASS** | 当該用途の MUST を一次資料で充足 |
| **PARTIAL** | 一部充足。条件付き / 用途限定 |
| **FAIL** | MUST 欠落、または推測が必要 |
| **UNKNOWN** | 公開一次資料では判定不能 |

**禁止:** 推測で PASS。`validFrom`/`delistingDate`/`API date`/`fetchedAt`/`updatedAt` を historical `knownAt` に転用して PASS しない。

用途は必ず分離:

- **A Retrospective Backtest Master**
- **B Forward Research Master**
- **C Live Trading Master**

---

## 2. Summary matrix

| Source | stable ID | ticker history | venue/MIC | currency | delisted | share class | historical knownAt | revision | cost | Retrospective | Forward | Live |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| OpenFIGI | PASS (FIGI layers) | FAIL | PARTIAL (`exchCode`) | FAIL (filter≠trading) | PARTIAL (inactive search) | PASS (shareClassFIGI) | FAIL | FAIL | free | **FAIL** | **PARTIAL** | **PARTIAL** |
| EODHD | PARTIAL (ISIN/FIGI map; Code is ticker-like) | PARTIAL (US symbol-change effective only) | PARTIAL (Exchange / OperatingMIC on exchange details) | PARTIAL (list Currency; no PIT history) | PASS (delisted=1) | UNKNOWN/PARTIAL (name text only) | FAIL | FAIL | free / $19.99+/mo | **FAIL** | **PARTIAL** | **PARTIAL** |
| Tiingo | PARTIAL (`permaTicker`) | FAIL (no change ledger) | PARTIAL (`exchangeCode`) | FAIL/PARTIAL (`reportingCurrency`≠trading; EOD metaにcurrency無し) | PARTIAL (`isActive` / endDate) | UNKNOWN | FAIL | FAIL | free / paid tiers | **FAIL** | **PARTIAL** | **PARTIAL** |
| Massive/Polygon | PASS (composite/share_class FIGI) | PARTIAL (Ticker Events + `date` as-of) | PASS (`primary_exchange` MIC) | PARTIAL (`currency_name`/`currency_symbol`; history/PIT弱) | PASS (`active=false`, `delisted_utc`) | PASS (share_class_figi; GOOG/GOOGL分離可能) | FAIL (`date`≠knownAt; `last_updated_utc`≠historical knownAt) | FAIL/PARTIAL | $0 / $29+ | **FAIL** | **PARTIAL** | **PARTIAL** |
| Alpha Vantage (reuse) | FAIL (ticker中心) | FAIL | FAIL/UNKNOWN | FAIL | UNKNOWN | FAIL | FAIL | FAIL | free/premium | **FAIL** | **FAIL** | **FAIL** |
| Institutional (CRSP/BBG/FactSet/LSEG) | PARTIAL/UNKNOWN（候補ID体系あり; specific product未確認） | PARTIAL/UNKNOWN（一般に履歴製品あり; 未突合） | PARTIAL/UNKNOWN | PARTIAL/UNKNOWN | PARTIAL/UNKNOWN | PARTIAL/UNKNOWN | UNKNOWN（specific entitlement未確認; announcement knownAtは別） | UNKNOWN/PARTIAL | QUOTE / HIGH | **UNKNOWN（技術候補; C未確定）** | **UNKNOWN/PARTIAL** | **UNKNOWN/PARTIAL** |
| Combo: OpenFIGI + EODHD/Tiingo/Massive + self-archive | PARTIAL | PARTIAL | PARTIAL | PARTIAL | PARTIAL | PARTIAL | FAIL（possession≠provider knownAt; 遡及禁止） | PARTIAL（possession ledgerのみ） | low–mid | **FAIL** | **PARTIAL** | **PARTIAL** |

---

## 3. OpenFIGI

### 3.1 一次資料

- https://www.openfigi.com/api/documentation  
- https://www.openfigi.com/api/overview  
- https://www.openfigi.com/docs/figi-allocation-rules.pdf  

### 3.2 所見

| 項目 | 判定 | 根拠 |
| --- | --- | --- |
| stable external ID | PASS | `figi` / `compositeFIGI` / `shareClassFIGI` |
| identifier namespace | PASS | FIGI standard namespaces |
| 粒度 | PASS（区別可能） | venue-level FIGI ≠ composite ≠ share class（Allocation Rules） |
| ticker | PASS（current属性） | mapping/search 応答の `ticker` |
| ticker history / validFrom/To | FAIL | 無料 API に期間付き ticker 版履歴なし |
| ticker recycle | FAIL | 履歴版が無いため retrospective 再構成不能 |
| exchange / MIC | PARTIAL | `exchCode` あり。ISO MIC 完全性は UNKNOWN |
| trading currency | FAIL | currency は filter 用途。trading currency evidence ではない（Acceptance / price entitlement と整合） |
| listing/delisting dates | FAIL/UNKNOWN | 公開 mapping 応答に標準 listing/delisting date なし |
| inactive/delisted | PARTIAL | search は active/inactive を横断可能と一次資料記載。完全 master ではない |
| share class | PASS | `shareClassFIGI` で class 集約。GOOG/GOOGL を同一 class に潰す用途ではない（listing/composite は別） |
| issuer relation | FAIL | CIK/issuer 関係の Security Master としては不足 |
| historical knownAt | FAIL | publication/knowledge timestamp なし |
| revision ledger | FAIL | 版履歴 API なし |
| cost | free | API key で rate 緩和。無料 |
| license | mapping/reuse は FIGI 標準方針。製品再配布は別確認 | 詳細 ToS は実装前に再確認 |

### 3.3 用途判定

| 用途 | 判定 | 理由 |
| --- | --- | --- |
| Retrospective | **FAIL** | ticker history / currency / knownAt / delisting completeness の MUST 欠落 |
| Forward | **PARTIAL** | identity mapping 補助として有用。単独 Master ではない |
| Live | **PARTIAL** | 同上 |

**重要:** 「FIGI がある」≠ Security Master PASS。

### 3.4 MUST traceability（Retrospective）

| MUST | status |
| --- | --- |
| permanent external id + namespace | satisfied |
| ticker + validFrom/To history | unsatisfied |
| trading currency + PIT | unsatisfied |
| delisted/inactive retention | unknown/partial |
| share class distinction | satisfied (ID layer) |
| historical knownAt | unsatisfied |
| revision evidence | unsatisfied |

---

## 4. EODHD

### 4.1 一次資料

- https://eodhd.com/financial-apis/exchanges-api-trading-hours-and-stock-market-holidays  
- https://eodhd.com/financial-apis/delisted-stock-companies-data-2  
- https://eodhd.com/financial-apis/id-mapping-api-cusip-isin-figi-lei-cik-%E2%86%94-symbol  
- https://eodhd.com/pricing  
- https://eodhistoricaldata.github.io/EODHD-openapi/redoc.html  

### 4.2 所見

| 項目 | 判定 | 根拠 |
| --- | --- | --- |
| stable ID | PARTIAL | `Code` は ticker 的。ISIN（欠損あり）、ID Mapping で FIGI/CUSIP/CIK。vendor permanent id としての独立性は弱い |
| ticker | PASS（current/delisted list） | exchange-symbol-list |
| ticker history | PARTIAL | `symbol-change-history`（**US only**, `effective` 日付）。knownAt ではない。全履歴の完全性は未証明 |
| validFrom/To as SecurityIdentifier versions | FAIL/PARTIAL | effective はあるが版付き master としての knownAt/provenance 不足 |
| exchange / MIC | PARTIAL | list の Exchange；exchange-details に OperatingMIC 等 |
| trading currency | PARTIAL | symbol list の `Currency`。**current/delisted snapshot**。currency change PIT ledger は確認できず |
| listingDate | UNKNOWN | 標準 symbol list フィールドに無し（fundamentals 依存は未深掘り・未取得） |
| delistingDate | PARTIAL | delisted 集合は取れる。正確な delistingDate フィールドの一貫性は UNKNOWN |
| delisted retention | PASS（取得経路） | `delisted=1` で別集合。active と非重複 |
| share class | UNKNOWN/PARTIAL | company_name に Class A 等が出る例あり。構造化 class ID は弱い |
| issuer relation | PARTIAL | CIK via id-mapping（Issuer 側）。Security 直結禁止は維持 |
| historical knownAt | FAIL | effective/fetched を knownAt にできない |
| revision | FAIL | current overwrite risk。版 ledger なし |
| cost | free 20 calls/day；EOD All World **$19.99/mo**；All-in-one **$99.99/mo** | 公式 pricing |
| commercial | personal vs commercial 分離 | commercial は高額帯 |

### 4.3 用途判定

| 用途 | 判定 |
| --- | --- |
| Retrospective | **FAIL**（knownAt / 完全 ticker history / currency PIT 不足。current+delisted list から過去 master 捏造禁止） |
| Forward | **PARTIAL**（明示 Currency + delisted 監視 + self-archive 前提） |
| Live | **PARTIAL** |

### 4.4 MUST traceability（Retrospective）

| MUST | status |
| --- | --- |
| permanent external id | partial/unsatisfied（ticker-like Code 依存リスク） |
| ticker history periods | partial（US change effective only） |
| trading currency + PIT | unsatisfied |
| delisted retention | satisfied (list path) |
| historical knownAt | unsatisfied |
| revision | unsatisfied |

---

## 5. Tiingo

### 5.1 一次資料

- Tiingo OpenAPI / docs: DailyMeta (`ticker`, `exchangeCode`, `startDate`, `endDate`)  
- https://www.tiingo.com/documentation/fundamentals（`permaTicker`, `isActive`, `reportingCurrency`）  
- https://www.tiingo.com/pricing  

### 5.2 所見

| 項目 | 判定 | 根拠 |
| --- | --- | --- |
| stable ID | PARTIAL | `permaTicker` = permanent Tiingo mapping（公式: primary key 用途）。粒度（share class/listing）の完全定義は追加確認が必要 → 独断 PASS しない |
| ticker | PASS | DailyMeta / fundamentals meta |
| ticker history | FAIL | change ledger / validFrom-To 版系列なし |
| startDate/endDate | PARTIAL | **価格データ可用性**の最早/最遅。ticker validity や listingDate と同義にしてはならない |
| exchange | PARTIAL | `exchangeCode` |
| MIC | UNKNOWN | exchangeCode→MIC の公式完全写像は未確認 |
| trading currency | FAIL/PARTIAL | EOD DailyMeta に currency 無し。fundamentals `reportingCurrency` は **SEC報告通貨**であり trading currency ではない |
| delisted | PARTIAL | `isActive`；endDate。完全 delisted universe entitlement は UNKNOWN |
| share class | UNKNOWN | permaTicker が class 単位かの一次定義が不十分 |
| historical knownAt | FAIL | なし |
| revision | FAIL | metadata overwrite；版 ledger なし |
| cost | free tier + paid（公式 pricing ページ；金額表示は動的/JSのため詳細は要再確認）。既存 price レビューでは internal $30/$50 帯の記載あり → 本表では **paid / UNKNOWN exact without live page parse** を併用 | 経済は ¥10k に対し有料は重い |

### 5.3 用途判定

| 用途 | 判定 |
| --- | --- |
| Retrospective | **FAIL** |
| Forward | **PARTIAL**（permaTicker + self-archive；currency は別 evidence 必須） |
| Live | **PARTIAL** |

---

## 6. Massive / Polygon

### 6.1 一次資料

- https://massive.com/docs/rest/stocks/tickers/all-tickers  
- https://massive.com/docs/rest/stocks/tickers/ticker-overview  
- https://massive.com/knowledge-base/article/what-does-massive-do-with-delisted-tickers  

### 6.2 所見

| 項目 | 判定 | 根拠 |
| --- | --- | --- |
| stable ID | PASS | `composite_figi`, `share_class_figi` |
| namespace | PASS | OpenFIGI 系 |
| ticker | PASS | `ticker` |
| as-of query | PARTIAL | `date` で「その日に利用可能な ticker 情報」を取得可能 |
| as-of ≠ knownAt | **明示** | Acceptance どおり `date` パラメータは historical knownAt ではない |
| ticker history | PARTIAL | Ticker Events（ticker change）。完全性/announcement knownAt は未確立 |
| venue/MIC | PASS | `primary_exchange`（ISO MIC） |
| currency | PARTIAL | `currency_name` / `currency_symbol`。trading currency と読めるが **history/PIT ledger** は弱い |
| listingDate | PARTIAL | overview の `list_date` |
| delisted | PASS | `active=false`, `delisted_utc`；KB で survivorship 回避を主張 |
| share class | PASS | `share_class_figi` で GOOG/GOOGL 分離可能（1例成功≠全市場PASS） |
| issuer | PARTIAL | `cik` は Issuer 側。Security 直結禁止 |
| historical knownAt | FAIL | `last_updated_utc` は「情報の正確性時点」であり、過去 decision の knowledge time ledger ではない |
| revision | FAIL/PARTIAL | as-of snapshot 再取得は可能だが、訂正版履歴 API としての明示は弱い |
| cost | Stocks Basic **$0**（2y等制限）；Starter **$29**/mo 等 | 公式 docs / pricing |
| history depth | Basic 2y；Starter+ deeper | Plan History 表 |

### 6.3 用途判定

| 用途 | 判定 | 理由 |
| --- | --- | --- |
| Retrospective | **FAIL** | identity/as-of/delisted は相対的に強いが、**knownAt MUST 未充足**。as-of を knownAt に昇格禁止 |
| Forward | **PARTIAL** | FIGI + MIC + currency field + self-archive で条件付き |
| Live | **PARTIAL** | 同上。broker mapping は別 |

### 6.4 MUST traceability（Retrospective）

| MUST | status |
| --- | --- |
| permanent external id | satisfied |
| ticker history periods | partial |
| venue/MIC | satisfied |
| trading currency + PIT | partial/unsatisfied |
| delisted retention | satisfied |
| share class | satisfied (FIGI layer) |
| historical knownAt | **unsatisfied** |
| revision ledger | unsatisfied/partial |

---

## 7. Alpha Vantage（再利用・深掘りなし）

既存 Price / entitlement レビューどおり Security Master 目的では **FAIL**（ticker中心、currency無し、knownAt無し、delisted不明）。新規調査なし。

---

## 8. Institutional（LSEG / Bloomberg / FactSet / CRSP）

**位置づけ:** **C の技術候補**（高額 vendor が Retrospective を満たし得るかを概観するのみ）。  
**今回:** specific product / entitlement が Acceptance Criteria の全 MUST を満たすことは **未確認**。  
したがって **「institutional なら成立する」とは断定しない**。**C 確定ではない**。

### 8.1 技術（候補概観・PASS断定ではない）

公開一次資料・製品一般知識に基づく **候補能力の概観**。特定契約 SKU との MUST 突合は未実施。

| Vendor | stable ID | ticker/name history | delisted | share class | PIT/symbology | 技術メモ |
| --- | --- | --- | --- | --- | --- | --- |
| **CRSP** | 候補 (`PERMNO` / `PERMCO`) | 候補 (`STOCKNAMES` NAMEDT/NAMEENDDT) | 候補（研究用 inactive） | 候補（PERMNO単位） | validity history は強い可能性。announcement knownAt は別監査 | 研究用 identity の定番候補 |
| **Bloomberg** | 候補 (FIGI / proprietary) | 候補（Data License symbology） | 候補（typical） | 候補 | 製品依・未突合 | 高額 |
| **FactSet** | 候補 (`FSYM_ID` -S/-R/-L) | 候補（symbology） | 候補（typical） | 候補 | 製品依・未突合 | 高額 |
| **LSEG** | 候補（RIC等） | 候補（typical） | 候補 | 候補 | 製品依・未突合 | 高額 |

### 8.2 経済

| 評価 | 値 |
| --- | --- |
| cost | **QUOTE / UNKNOWN**（公開定価なし。端末・Data License は通常高額） |
| vs ¥10,000 運用 | **ECONOMICALLY_UNSUITABLE**（見込み） |
| 技術との分離 | 経済不適合を理由に技術を FAIL/D へ落とさない。一方、specific product 未確認のため **技術 PASS / C 確定にもしない** |

### 8.3 用途判定

| 用途 | 判定 |
| --- | --- |
| Retrospective | **UNKNOWN（技術候補; specific product未確認）** / 経済は UNSUITABLE 見込み |
| Forward | **UNKNOWN/PARTIAL（技術候補; 未確認）** / 経済不適合見込み |
| Live | **UNKNOWN/PARTIAL（技術候補; 未確認）** / 経済不適合・再配布制約見込み |

---

## 9. Source combination

### 9.1 候補

`OpenFIGI`（class/composite FIGI）  
+ `Massive` or `EODHD`（delisted / exchange / currency field）  
+ **self-archive**（取得・検証済みの **possession evidence** を固定。`fetchedAt` / `ingestedAt` は provider historical `knownAt` ではない）

### 9.2 安全 join 要件（満たせない場合は禁止）

- stable external identifier（推奨: share_class_figi / composite_figi）
- namespace
- validity period
- provenance
- provider historical `knownAt`（無い場合は retrospective PASS 不可。self-archive で代替生成しない）
- share class 一致
- listing 一致

**self-archive の意味（契約維持）:**

- 得られるのは **possession evidence**（いつ自システムが取得・検証したか）
- `fetchedAt` / `ingestedAt` ≠ provider historical `knownAt`
- archive 開始後は、取得・検証済み時刻以降だけを **forward decision eligibility boundary** として保守的に使える
- historical provider `knownAt` を自己生成したとは扱わない
- retrospective 区間への遡及禁止

**禁止:** ticker 文字列だけの join。

### 9.3 判定

| 用途 | 判定 |
| --- | --- |
| Retrospective | **FAIL**（provider knownAt 欠如。possession を knownAt に昇格・遡及しない） |
| Forward | **PARTIAL / CONDITIONAL**（verified possession 以降の eligibility boundary が確立した場合のみ） |
| Live | **PARTIAL / CONDITIONAL** |

---

## 10. 横断評価

### 10.1 historical knownAt（最重要）

| Source | retrospective knownAt 再構成 |
| --- | --- |
| OpenFIGI | FAIL |
| EODHD | FAIL（`effective` ≠ knownAt） |
| Tiingo | FAIL |
| Massive | FAIL（`date` as-of ≠ knownAt；`last_updated_utc` ≠ historical knownAt） |
| Institutional | UNKNOWN（specific product/entitlement 未確認）。announcement-time knownAt は別監査が必要な場合あり |
| Combo + self-archive | FAIL as provider knownAt。forward では verified possession 以降のみ eligibility PARTIAL |

### 10.2 ticker history / recycle

| Source | change | recycle | 判定 |
| --- | --- | --- | --- |
| OpenFIGI | 無し | 不可 | FAIL |
| EODHD | US effective list | 部分的 | PARTIAL |
| Tiingo | 無し | permaTicker で recycle耐性主張はあり得るが history版なし | FAIL/PARTIAL |
| Massive | Ticker Events + date | PARTIAL | PARTIAL |
| current-only | — | — | Retrospective **FAIL** |

### 10.3 currency

| Source | field意味 | period-valid | 判定 |
| --- | --- | --- | --- |
| OpenFIGI | filter | no | FAIL |
| EODHD | list Currency（trading寄り） | snapshot | PARTIAL |
| Tiingo | reportingCurrency（報告通貨） | n/a | FAIL as trading currency |
| Massive | currency_name/symbol | weak history | PARTIAL |
| 曖昧 | — | — | PASS 禁止 |

### 10.4 delisted / share class

- **Delisted経路あり:** EODHD、Massive（相対的に強い）。Tiingo PARTIAL。OpenFIGI PARTIAL。  
- **Share class:** OpenFIGI shareClassFIGI / Massive share_class_figi が構造化。Alphabet GOOG/GOOGL は **分離可能な証拠**になり得るが、1例成功を全市場 PASS にしない。

### 10.5 経済（¥10,000 運用）

| Source | cost band | 経済評価 |
| --- | --- | --- |
| OpenFIGI | free | ECONOMICALLY_SUITABLE（ただし技術不足） |
| EODHD paid | ~$20–100/mo | ECONOMICALLY_UNSUITABLE〜BORDERLINE |
| Massive Starter+ | $29+/mo | ECONOMICALLY_UNSUITABLE |
| Tiingo paid | paid | ECONOMICALLY_UNSUITABLE（概算） |
| Institutional | QUOTE/HIGH | ECONOMICALLY_UNSUITABLE |

技術と経済は分離。経済不適合を理由に技術を FAIL/D へ落とさない。  
ただし institutional は specific product 未確認のため、経済以前に **技術 PASS / C も未確定**。

---

## 11. Acceptance Criteria traceability（総括）

Retrospective Backtest Master の MUST に対する現状:

| MUST | OpenFIGI | EODHD | Tiingo | Massive | Institutional | Combo+archive |
| --- | --- | --- | --- | --- | --- | --- |
| permanent id + namespace | satisfied | partial | partial | satisfied | unknown（候補あり; 未突合） | satisfied |
| ticker validity history | unsatisfied | partial | unsatisfied | partial | unknown（候補あり; 未突合） | partial |
| trading currency + PIT | unsatisfied | unsatisfied | unsatisfied | partial/unsatisfied | unknown | unsatisfied* |
| delisted retention | partial | satisfied | partial | satisfied | unknown（候補あり; 未突合） | partial |
| share class safety | satisfied | unknown | unknown | satisfied | unknown（候補あり; 未突合） | satisfied |
| historical knownAt | **unsatisfied** | **unsatisfied** | **unsatisfied** | **unsatisfied** | **unknown**（未突合） | **unsatisfied*** |
| revision evidence | unsatisfied | unsatisfied | unsatisfied | partial | unknown（候補あり; 未突合） | unsatisfied* |

\* Combo+archive の self-archive は possession / `fetchedAt`・`ingestedAt` であり、provider historical `knownAt` を満たさない。forward eligibility boundary のみ条件付き。retrospective 遡及は unsatisfied。

---

## 12. 最終判定

### 12.1 A/B/C/D

**D. 現時点で Retrospective Security Master 不成立**

最終 **D** の理由:

1. 無料 / 低コスト retail（単独・組合せ）では historical `knownAt` 等の MUST を一次資料で満たせない  
2. institutional は **C の技術候補**にとどまり、今回 specific product / entitlement の PASS は **未確認** → **C 確定ではない**  
3. 加えて ¥10,000 運用前提では institutional は経済的に採用不能見込み  
4. したがって **技術的成立性未確定 + 採用不能** のため最終 **D**  
5. **経済不適合を理由に技術を D へ落としたわけではない**（技術 PASS も未確認）

「institutional なら成立する」とは **断定しない**。

補足ラベル:

| 経路 | ラベル |
| --- | --- |
| 無料単独/組合せで Retrospective | 不可（≠A） |
| 低コスト retail で Retrospective | 不可（≠B） |
| 高額 institutional | **C の技術候補**（specific product 未確認 → **C 未確定**。経済は UNSUITABLE 見込み） |
| 現状の採用可能宣言 | **D** |

### 12.2 Forward / Live

| Master | 判定 |
| --- | --- |
| Forward Research Master | **CONDITIONAL**（FIGI系 + explicit currency evidence + delisted監視 + self-archive の **possession / eligibility boundary**。provider knownAt 自己生成ではない。実装・契約なし） |
| Live Trading Master | **CONDITIONAL**（同上 + broker-side mapping は別。楽天依存は Criteria 外） |

### 12.3 Real Backtest Gate

Security Master が仮に将来 PASS しても自動 GO にしない。既存 blocker 維持:

- Price historical knownAt  
- Corporate Action PIT  
- Universe entitlement  
- Dividend Grade A/B  
- Fundamentals knownAt  

**Real Backtest: NO-GO**

---

## 13. Blockers

### Critical

1. Retail sources に **historical knownAt / immutable version ledger** が無い  
2. trading currency の **period-valid + knowledge-time** evidence 不足  
3. ticker history の完全な validFrom/To 版系列不足（recycle 再構成不能）  
4. ticker-only join 誘惑（契約で禁止・維持）  

### High

1. EODHD symbol-change が US-only / effective≠knownAt  
2. Tiingo `reportingCurrency` と trading currency の混同リスク  
3. Massive `date` as-of の knownAt 誤用リスク  
4. share class 構造化が source により UNKNOWN  
5. 有料 retail / institutional の ¥10k 不整合  
6. Price/CA/Universe 等の既存 Critical blocker（Master 外）

---

## 14. Sample evidence 方針

公開 docs のフィールド定義で評価。  
AAPL/MSFT/GOOG/GOOGL・ticker change・delisted の **live API 取得は未実施**（API key/有料契約なし）。  
fixture を live 証拠扱いしない。

GOOG/GOOGL については、OpenFIGI/Massive の **share class / listing FIGI 分離モデル**が Acceptance の「潰さない」要件と整合し得る、と一次資料上判断（全市場 PASS ではない）。

---

## 15. Next step（実装しない）

1. 本 PoC を PR で固定する。  
2. Forward CONDITIONAL を進めるなら、**self-archive（possession / eligibility boundary）運用設計ノートのみ**（client 実装禁止のまま。`fetchedAt`≠historical `knownAt` 維持）。  
3. Retrospective を求めるなら institutional の **specific product / entitlement MUST 突合** と見積（QUOTE）・経済レビューを分離実施（C は突合後まで未確定）。  
4. Real Backtest は Master 以外の knownAt/CA/Universe/Dividend も揃うまで NO-GO。  

---

## 16. Build / test

**文書のみのため未実行。**

## 17. Code changes

**なし。**
