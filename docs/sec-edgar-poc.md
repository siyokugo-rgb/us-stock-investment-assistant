# SEC EDGAR Submissions Metadata PoC

Phase -1 Feasibility PoC（提出メタデータのみ）。  
財務数値 PIT・価格 PIT・配当 PIT・戦略有効性の証明ではない。

基準 `main`: `97f99f5c3c43bfb7d7e6dac4561aed80ef0a4a07`  
実施日（UTC）: 2026-09-11

## 1. 目的

SEC EDGAR の**実データ**で次を確認する。

1. submissions メタデータとして何が取得できるか
2. PIT の `knownAt` をどの根拠まで保証できるか
3. Issuer / Security 分離が実データ上必要か

今回は SEC クライアント完成・戦略・Android・Quality・QDR・Backtest を目的としない。

## 2. 公式仕様参照

確認に用いた SEC 公式情報:

- [EDGAR Application Programming Interfaces (APIs)](https://www.sec.gov/search-filings/edgar-application-programming-interfaces)  
  - endpoint: `https://data.sec.gov/submissions/CIK##########.json`  
  - CIK は 10 桁ゼロ埋め  
  - API key 不要
- [Accessing EDGAR Data](https://www.sec.gov/search-filings/edgar-search-assistance/accessing-edgar-data) / Developer FAQ  
  - 識別可能な User-Agent が必要  
  - Fair Access（目安: 合計 10 requests/second 以下）  
- CIK 解決: `https://www.sec.gov/files/company_tickers.json`（Ticker からの推測は禁止）

## 3. Endpoint / 対象 CIK

| 対象 | CIK（10桁） | 確認方法 |
| --- | --- | --- |
| Apple Inc. | `0000320193` | company_tickers.json（AAPL） |
| MICROSOFT CORP | `0000789019` | company_tickers.json（MSFT） |
| Alphabet Inc. | `0001652044` | company_tickers.json（GOOGL / GOOG 同一 CIK） |

Endpoint 例:

`https://data.sec.gov/submissions/CIK0000320193.json`

User-Agent は環境変数 `SEC_EDGAR_USER_AGENT` で注入。未設定・placeholder は Fail-Closed。  
PoC 実行時は 1 request / 秒以上の間隔を空けた。

## 4. 実 HTTP 取得結果（live）

実行: `./gradlew --no-daemon secEdgarPoc`（および同一 endpoint の再取得で hash 記録）

| 対象 | HTTP | bytes | SHA-256 |
| --- | ---: | ---: | --- |
| AAPL | 200 | 164091 | `cb90ffafc5b6f997b60aa109e07008223ad35abe896ec7918fd43652b4057329` |
| MSFT | 200 | 184681 | `d68c1960bb0f6a4fb2cc10a93241fc410f971536577aa9ecc60f9409b68e24ec` |
| Alphabet | 200 | 152715 | `d5c5ad376392b9d38a7d926506f1b0fd34c1c9e2757b97392c863ddaea70e225` |

`ingestedAt` / `fetchedAt` は取得時刻（例: 2026-09-11T04:48:03Z 付近）。  
これは historical `knownAt` ではない。

User-Agent 未設定時の live タスクは失敗（exit != 0）。mock への自動切替なし。

## 5. 実 JSON で確認したフィールド

### Issuer 側（top-level、実測）

少なくとも次を確認した（推測ではない）:

- `cik`
- `name`
- `entityType`
- `sic` / `sicDescription`
- `tickers`（配列）
- `exchanges`（配列）
- `formerNames`（配列。AAPL は 3 件。要素に `name` / `from` / `to`）
- `ein`, `lei`, `category`, `stateOfIncorporation`, `fiscalYearEnd`, `filings` など

### filings.recent（columnar、実測）

- `accessionNumber`
- `filingDate`
- `reportDate`
- `acceptanceDateTime`
- `form`
- `primaryDocument`
- `primaryDocDescription`
- `isXBRL`
- `isInlineXBRL`
- `isXBRLNumeric`（公式ページの要約に必ずしも列挙されないが、**実レスポンスに存在**）
- ほか: `act`, `fileNumber`, `filmNumber`, `items`, `core_type`, `size`

### filings.files（追加 history）

例（AAPL）:

- `name`: `CIK0000320193-submissions-001.json`
- `filingCount`, `filingFrom`, `filingTo`

recent は直近最大おおよそ 1000 件。それ以前は `files` 側。

## 6. 日時フィールドと knownAt 評価

| フィールド | 実測の意味 | historical knownAt 評価 |
| --- | --- | --- |
| `reportDate` | 報告対象期間・事象日 | **UNUSABLE** |
| `filingDate` | 提出日（日付のみ） | **PARTIAL**（日境界まで。exact 時刻ではない） |
| `acceptanceDateTime` | EDGAR acceptance 時刻 | **PARTIAL**（lower-bound / conservative proxy 候補。CONFIRMED ではない） |
| `ingestedAt` / 取得時刻 | 本システムの取得時刻 | **UNUSABLE**（historical knowledge PIT 代理にしない） |
| sec.gov 初回 public dissemination | submissions JSON だけでは常時提供を確認できず | **UNVERIFIED** |

### acceptanceDateTime を CONFIRMED knownAt にしない理由

1. 公式 API が返すのは acceptance であり、「sec.gov で最初に public に利用可能になった正確な timestamp」と同一であることの証明にはならない。  
2. 古い history 行では `...T05:00:00.000Z` のように日付へ丸められた値が観察され、精度が一様でない。  
3. 「API に日時がある」こと自体は PIT-safe の証明にならない。

**結論:** `acceptanceDateTime = knownAt` の無条件固定は禁止。  
現状の正直な扱い: **PARTIAL（conservative lower-bound candidate）**。

## 7. Amendment 確認

### recent 内

| 発行体 | 10-K | 10-Q | 8-K | 10-K/A | 10-Q/A | 8-K/A |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| AAPL | あり | あり | あり | 0（recent） | 0（recent） | あり |
| MSFT | あり | あり | あり | 0（recent） | 0（recent） | あり |
| Alphabet | あり | あり | あり | 0（recent） | 0（recent） | 0（recent） |

合成データは作っていない。

### 別 accession / 別 acceptance の例（AAPL）

- Original 8-K: accession `0001140361-26-015711`, filingDate `2026-04-20`, acceptance `2026-04-20T21:29:51.000Z`, reportDate `2026-04-17`
- Amendment 8-K/A: accession `0001140361-26-035325`, filingDate `2026-09-01`, acceptance `2026-09-01T20:30:35.000Z`, reportDate `2026-04-17`

同一 reportDate でも **別 accession・別 acceptance**。上書き同一レコードとしては扱えない。

### history 側（AAPL `CIK0000320193-submissions-001.json`）

recent に無い **10-K/A（2）・10-Q/A（2）** を history で確認した。  
「recent に無い = 存在しない」ではない。

## 8. CIK / Issuer / Security 監査

Alphabet（CIK `0001652044`）の実測:

- `tickers`: `GOOGL`, `GOOG`, `GOOGM`, `GOOGN`
- `exchanges`: いずれも Nasdaq（配列長は tickers と対応）

**同一 CIK に複数 Ticker / share class 候補がぶら下がる。**  
CIK は Issuer（提出主体）側であり、1 株式 Security と 1:1 ではない。

Data Contract の「Issuer と Security を分離すべき」は、実データ事実として支持される。  
ただし本 PoC では `IssuerId` 実装は行わない（事実確定のみ）。

## 9. Fail-Closed 確認

確認した失敗経路:

- `SEC_EDGAR_USER_AGENT` 未設定 → live タスク失敗（成功扱いにしない）
- placeholder User-Agent（`example.com` 等）→ 設定時点で拒否
- 不正 CIK / malformed JSON / required field 欠損 → parser が例外（fixture test）
- HTTP 4xx/5xx → 成功扱いにせず例外（mock fallback なし）

## 10. fixture test / live PoC / build

| 区分 | 結果 |
| --- | --- |
| fixture unit tests（ネットワーク非依存） | PASS（`SecSubmissionsParserTest` 8 件を含む） |
| live PoC | PASS（3 CIK とも HTTP 200、parse 成功、hash 記録） |
| `./gradlew --no-daemon clean build` | PASS |
| 全 test | **50 tests, 0 failures, 0 errors, 0 skipped** |

fixture PASS と live PASS は区別して記録する。

## 11. Data Contract との差異・修正要否

既存 Data Contract の方向（Issuer/Security 分離、PIT 混同禁止、Fail-Closed）と整合。

**修正要否: 要（最小追記）**

- SEC `acceptanceDateTime` を historical `knownAt` の **CONFIRMED** 根拠にしないこと
- submissions メタデータ PoC で確認したフィールド実態への参照

本 PR で `docs/data-contract.md` に短い追記を行う。

## 12. 未解決 / Critical・High

### Critical

1. historical `knownAt` の CONFIRMED 根拠が submissions だけでは不足  
2. Historical Universe membership 未整備（本 PoC 範囲外・継続）  
3. Issuer モデル未実装（必要性は確認済み）

### High

1. acceptance と public dissemination の差を測る追加証跡がない  
2. 古い acceptance の丸め精度  
3. `files` history を含む完全な amendment グラフ未構築  
4. CompanyFacts/XBRL 数値は未検証（意図的）

## 13. 判定

### SE / Programmer / Data Integrity / QA

**PARTIAL**

理由:

- submissions メタデータの再現可能取得は成立した
- Issuer≠Security、amendment 別版、日時フィールドの限界は実データで明確になった
- しかし historical `knownAt` を CONFIRMED にするには不足（acceptance のみでは不十分）

「取得できた」ことだけを PASS 理由にしない。

## 14. 次工程へ進めるか

**条件付きで可。**

進めてよいもの:

- Data Contract への knownAt 注意の反映（本 PR）
- 次の狭い PoC 設計（例: 単一 accession の取得と版管理、または knownAt 証跡の追加調査）

まだ進めてはいけないもの:

- 財務数値の本実装
- 価格/配当 API
- Quality / QDR / Backtest
- Android
- acceptanceDateTime を CONFIRMED knownAt として固定する実装
