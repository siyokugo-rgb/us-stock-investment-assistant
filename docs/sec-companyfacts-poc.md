# SEC EDGAR XBRL CompanyFacts PoC

Phase -1 Feasibility PoC（財務数値の本番モデル実装前）。  
Quality / ROIC / FCF / QDR / Backtest / knownAt 完全解決の証明ではない。

基準 `main`: `71dffa1ddc1aa7b9c133ae8a34116108da42efa1`  
実施日（UTC）: 2026-09-11

## 1. 目的

SEC XBRL CompanyFacts API の実データで次を確認する。

1. JSON 構造（taxonomy / concept / unit / period / form / filed / frame / accn）
2. CIK は Issuer / filing entity 側であること（SecurityId 直結禁止）
3. `accn` 経由で submissions / filing artifact へ追跡できるか
4. 同一 concept/period の複数 version を潰さず候補集合として扱えるか
5. PIT / knownAt 上の限界

## 2. 公式仕様

参照:

- [EDGAR Application Programming Interfaces](https://www.sec.gov/search-filings/edgar-application-programming-interfaces)
  - CompanyFacts: `https://data.sec.gov/api/xbrl/companyfacts/CIK##########.json`
  - CIK は 10 桁ゼロ埋め
  - API key 不要
- [Accessing EDGAR Data](https://www.sec.gov/search-filings/edgar-search-assistance/accessing-edgar-data)
  - 識別可能な User-Agent
  - Fair Access（目安合計 10 req/s 以下）

本 PoC では 1 request あたり約 1.1s 以上の間隔を空けた。

## 3. Endpoint / 対象 CIK

| Issuer | CIK | 備考 |
| --- | --- | --- |
| Apple Inc. | `0000320193` | payload `cik=320193` を 10 桁正規化して一致確認 |
| MICROSOFT CORPORATION | `0000789019` | 同上 |
| Alphabet Inc. | `0001652044` | 複数 ticker/share-class 候補を持つ Issuer（既存 submissions PoC） |

Endpoint 例:

`https://data.sec.gov/api/xbrl/companyfacts/CIK0000320193.json`

User-Agent: 環境変数 `SEC_EDGAR_USER_AGENT`（未設定は Fail-Closed）。

## 4. Live HTTP 結果

実行: `./gradlew --no-daemon secCompanyFactsPoc`

| Issuer | HTTP | Bytes | SHA-256 | fetchedAt (UTC) |
| --- | ---: | ---: | --- | --- |
| Apple | 200 | 3789099 | `73a86c6aedc31f77cac2ea4df5f80f0b3bd7e6eb58bb4e01444fbedf3afb9c43` | 2026-09-11T07:12:33.354341738Z |
| Microsoft | 200 | 4881196 | `f8aae2965b20ad0df44bdf7ccbedf797d275b6b8dc030154a7a311361bb7246f` | 2026-09-11T07:12:38.234150049Z |
| Alphabet | 200 | 3164517 | `a017f26ee88cb4a639d1c1ddb06d53b4afe22f0ac89cabec7cd67c88919fe1c5` | 2026-09-11T07:12:38.417292410Z |

`fetchedAt` は HTTP 成功 → body 受信 → parse → request CIK 一致成功の直後。historical knownAt ではない。

巨大 raw JSON は repository に保存していない（hash + 構造観察のみ）。

## 5. JSON 実測構造

トップレベル（3社共通）:

- `cik`（number）
- `entityName`（string）
- `facts`（object: taxonomy → concept → `{label?, description?, units}`）

`units` は unit 名（例: `USD`, `pure`, `Year`）をキーとし、fact 配列を持つ。

fact 要素で観察したキー（concept により欠落あり）:

- `val`
- `end`（必須相当）
- `start`（期間概念のみ）
- `fy` / `fp`
- `form`
- `filed`
- `frame`（欠落し得る）
- `accn`

taxonomies 実測:

| Issuer | taxonomies |
| --- | --- |
| Apple | `dei`, `us-gaap` |
| Microsoft | `dei`, `us-gaap` |
| Alphabet | `dei`, `ecd`, `ffd`, `us-gaap` |

## 6. taxonomy / concept 例

観察した標準 US-GAAP（PoC で retain）:

| tag | 意味 | Apple | Microsoft | Alphabet |
| --- | --- | ---: | ---: | ---: |
| `RevenueFromContractWithCustomerExcludingAssessedTax` | Revenue (ASC 606) | USD=117 | USD=134 | USD=87 |
| `Revenues` | Revenues | USD=11 | USD=31 | USD=77 |
| `NetIncomeLoss` | Net Income (Loss) | USD=338 | USD=340 | USD=152 |
| `Assets` | Assets | USD=146 | USD=142 | USD=90 |
| `Liabilities` | Liabilities | USD=144 | USD=134 | USD=84 |
| `CashAndCashEquivalentsAtCarryingValue` | Cash | USD=228 | USD=266 | USD=121 |
| `FiniteLivedIntangibleAssetsUsefulLifeMaximum` | useful life | pure=1; Year=1 | pure=1; Year=3 | **欠落** |

企業ごとに tag の有無・件数・unit 構成が異なる。単一 tag の横断ハードコードは危険。

## 7. unit 例

- 金額系: 主に `USD`
- 複数 unit 例（Apple/Microsoft）: `FiniteLivedIntangibleAssetsUsefulLifeMaximum` → `pure` と `Year`
- unit 違いは別候補として保持（自動統合禁止）

## 8. period / fy / fp / frame

- `end` は instant / duration の期末
- `start` は duration（例: NetIncomeLoss）に存在し、Assets 等では欠落し得る
- `fy` / `fp`（FY, Q1..Q3 等）
- `frame`（例: `CY2025`, `CY2026Q2`）は欠落し得る。frame 欠落を「無効」と決めつけないが、比較キーに勝手に補完しない

## 9. accession 結合（Critical）

CompanyFacts fact の `accn` → submissions.recent 照合 および/または archive filing index 取得。

Apple `NetIncomeLoss` から実追跡した 3 件:

| form | fy/fp | end | filed | accn | in submissions.recent | archive index |
| --- | --- | --- | --- | --- | --- | ---: |
| 10-K | 2025/FY | 2025-09-27 | 2025-10-31 | `0000320193-25-000079` | true | 200 |
| 10-Q | 2026/Q3 | 2026-06-27 | 2026-07-31 | `0000320193-26-000020` | true | 200 |
| 10-K/A | 2009/FY | 2009-09-26 | 2010-01-25 | `0001193125-10-012091` | false（古い） | 200 |

追跡パス:

`CompanyFacts` → `accn` →（任意）`submissions` metadata → `Archives/.../{accn}-index.htm`

`accn` 欠落時は推測結合しない（Fail-Closed）。

## 10. duplicate / version

同一 concept（`NetIncomeLoss`）・同一 `end/fy/fp` に複数 accession が存在する例（Apple）:

| end | fy/fp | candidates | 観察 |
| --- | --- | ---: | --- |
| 2007-09-29 | 2009/FY | 2 | 10-K vs 10-K/A（値も異なる: 3496000000 vs 3495000000） |
| 2008-09-27 | 2009/FY | 2 | 10-K vs 10-K/A（restatement candidate） |
| 2009-09-26 | 2009/FY | 2 | 10-K vs 10-K/A |

Microsoft / Alphabet では同一値の 10-K と 8-K 再掲（frame 有無差）も観察。

扱い:

- 候補集合として保持
- 最新版自動採用禁止
- 配列末尾自動採用禁止
- amendment / later corrected / restated は **候補ラベル候補**であり、本 PoC では自動確定しない

## 11. knownAt 評価

| フィールド / 手段 | historical knownAt |
| --- | --- |
| CompanyFacts `filed` | **UNUSABLE**（filing 日付。exact public availability ではない） |
| CompanyFacts 単独 | 過去 `decisionAt` 判定には **不足** |
| `accn` → submissions `acceptanceDateTime` | 既存結論どおり **PARTIAL lower-bound**。CONFIRMED knownAt ではない |
| `fetchedAt` | 保有PITのみ。historical knownAt 代理禁止 |

結論:

1. 「現在 CompanyFacts に値がある」≠「当時その値を知っていた」
2. historical decisionAt 判定には **accession metadata（および必要なら artifact）への join が必須**
3. join しても acceptanceDateTime を CONFIRMED knownAt にしてはならない（PR #4/#5 結論を維持）

## 12. FundamentalSnapshot / SecurityId

- CompanyFacts を既存 `FundamentalSnapshot(securityId=...)` へ直接流し込まない
- CIK = Issuer / filing entity 側
- Alphabet のように同一 CIK に複数 ticker/share-class 候補がある Issuer では、Security 直結は特に危険
- `IssuerId` 本実装は今回禁止（必要性は高いが未実装）

## 13. Data Contract 反映

上記一般規則は [`data-contract.md`](data-contract.md) の **「SEC XBRL CompanyFacts 契約（正式）」** および §4.4 に正式反映済み。

本 PoC 文書は Source of Truth の**検証記録**であり、契約本文は `data-contract.md` を正とする。特定 issuer の固有件数・固有 tag 実測は契約へハードコードしない。

## 14. Fail-Closed

成功扱いにしない:

- HTTP 4xx/5xx / empty / malformed JSON
- request CIK ≠ payload CIK
- units 空 / required 構造欠損
- invalid `accn`
- `accn` 無しの version 推測結合
- CompanyFacts → SecurityId 推測割当
- mock fallback

## 15. テスト

`SecCompanyFactsFailClosedTest`（local HttpServer + sanitized fixture）:

1. valid parse
2. HTTP 404
3. HTTP 500
4. empty body
5. malformed JSON
6. CIK mismatch
7. concept 複数 unit 保持
8. 同一 concept/period の複数 accession を潰さない
9. invalid accn Fail-Closed
10. failure 時 fetchedAt 非打刻
11. 成功時 body/parse/CIK 後に fetchedAt
12. SecurityId 自動割当しない（モデル責務）
13. concept `units` 欠損 → Fail-Closed（fetchedAt 非打刻）
14. `facts` 空 → Fail-Closed

## 16. 未解決 / 残 Critical・High

| 重要度 | 項目 |
| --- | --- |
| Critical | historical knownAt は未解決（CompanyFacts 単独不可） |
| Critical | IssuerId 未実装のまま Security へ落とすと誤割当リスク |
| High | concept 正規化辞書（企業差・deprecated tag）未整備 |
| High | restatement / amendment 採用規則未定義（候補保持のみ） |
| High | frame 有無・単位差の比較キー設計が未確定 |
| Medium | CompanyFacts 全 concept 永続化・DB・Provider 本実装は対象外 |

## 17. 最終判定

| 観点 | 判定 | 理由 |
| --- | --- | --- |
| SE | PARTIAL | API/構造/結合は確認。Issuer モデルと PIT 本解決は未了 |
| Programmer | PASS | 最小 client/parser/test/live。過剰抽象化なし |
| Data Integrity | PARTIAL | 版候補保持・accn 結合は確認。knownAt/Security 直結は禁止維持 |
| QA | PASS | local Fail-Closed + live 分離実行 |

**総合: PARTIAL**

取得成功だけを PASS 理由にしない。  
本 PoC を財務指標完成・knownAt 完全解決・Quality/QDR/Backtest 有効性の証明として扱わない。

## 18. 次工程へ進めるか

**条件付きで可。**  
次は Issuer 責務の明確化（IssuerId 設計）または concept 正規化/version 採用規則の契約化が妥当。  
Quality / QDR / Backtest / FundamentalSnapshot 本番マッピングへは進めるべきでない。
