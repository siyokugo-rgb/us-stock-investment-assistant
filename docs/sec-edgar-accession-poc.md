# SEC EDGAR Accession / Filing Artifact PoC

Phase -1 Feasibility PoC（accession 単位の filing 原本取得・版分離・provenance）。  
財務数値 PIT・knownAt 完全解決・戦略有効性の証明ではない。

基準 `main`: `987478827ac28ebfc5cf0565d2b9ec23b993d978`  
実施日（UTC）: 2026-09-11

## 1. 目的

SEC EDGAR の**実データ**で次を確認する。

1. 「1 filing = 1 accession」を基準に、filing 原本を再現可能に取得できるか
2. accession を安定した提出版 ID として扱えるか
3. original / amendment を上書きせず別版として保持できるか
4. raw artifact と metadata の provenance をどこまで固定できるか

今回は CompanyFacts / XBRL 値抽出 / Quality / QDR / Backtest / 財務数値解析を目的としない。

## 2. 公式仕様参照

確認に用いた SEC 公式情報:

- [Accessing EDGAR Data](https://www.sec.gov/search-filings/edgar-search-assistance/accessing-edgar-data)
  - Accession number は accepted submission に付与される一意識別子
  - 先頭 10 桁は **submission を行った entity の CIK**（会社本人とは限らず、third-party filer があり得る）
  - Post-EDGAR 7.0 archive パス:
    - `/Archives/edgar/data/{issuerCikNoLeadingZeros}/{accessionWithoutDashes}/`
    - filing index: `{accession}-index.htm`（および `.html`）
    - complete submission text: `{accession}.txt`
  - 例示パスでは subject company CIK と accession 先頭 CIK が一致しないケースが公式に示されている
- [EDGAR Application Programming Interfaces](https://www.sec.gov/search-filings/edgar-application-programming-interfaces)
  - submissions: `https://data.sec.gov/submissions/CIK##########.json`
- Fair Access / 識別可能な User-Agent 必須

URL は推測だけで固定せず、公式記載と live archive 応答の両方で確認した。

## 3. 対象 issuer / accession

| 項目 | 値 |
| --- | --- |
| Issuer | Apple Inc. |
| Issuer CIK | `0000320193` |
| Original candidate | accession `0001140361-26-015711` / form `8-K` / filingDate `2026-04-20` / reportDate `2026-04-17` / primary `ef20071035_8k.htm` |
| Amendment candidate | accession `0001140361-26-035325` / form `8-K/A` / filingDate `2026-09-01` / reportDate `2026-04-17` / primary `ef20081427_8ka.htm` |

選定理由:

- 同一 `reportDate=2026-04-17` の 8-K と 8-K/A が submissions に存在
- ただし **form + reportDate 一致だけでは関係 CONFIRMED としない**

## 4. Accession identity 監査

| 観察 | 結果 |
| --- | --- |
| accession は提出版の識別子として保持 | YES（store key = accession） |
| original と /A は別 accession | YES（`...-015711` vs `...-035325`） |
| 同一 accession の後勝ち上書き | 拒否（`SecAccessionArtifactStore.put` Fail-Closed） |
| accession を SecurityId / IssuerId の代わりに使う | 禁止（コード・文書で明示） |
| accession 先頭 CIK | 両accessionとも `0001140361` |
| issuer CIK | `0000320193` |
| 先頭 CIK == issuer CIK | **false**（third-party filer） |
| archive パス CIK | subject issuer `320193`（先頭ゼロ除去） |

**結論:** accession 先頭 10 桁は submitting entity CIK であり、投資対象 Security / Issuer の恒久 ID ではない。

## 5. Artifact 取得結果（live）

実行: `./gradlew --no-daemon secEdgarAccessionPoc`  
User-Agent: 環境変数 `SEC_EDGAR_USER_AGENT`（未設定は Fail-Closed）

### Original `0001140361-26-015711`

| Artifact | Exact URL | HTTP | Content-Type | Bytes | SHA-256 | fetchedAt |
| --- | --- | ---: | --- | ---: | --- | --- |
| filing index | `https://www.sec.gov/Archives/edgar/data/320193/000114036126015711/0001140361-26-015711-index.htm` | 200 | text/html | 10281 | `9a4fea0aea8b654d7412bf2ac5bc2e02548a9a9a91ea2bad189a6728a4cb8fd6` | 2026-09-11T06:17:18.332659846Z |
| primary document | `https://www.sec.gov/Archives/edgar/data/320193/000114036126015711/ef20071035_8k.htm` | 200 | text/html | 38605 | `044c055234b6a75fe74ff34dfc45c799dbb4507a2df82950962c39cefd1e9351` | 2026-09-11T06:17:19.295803054Z |
| complete submission text | `https://www.sec.gov/Archives/edgar/data/320193/000114036126015711/0001140361-26-015711.txt` | 200 | text/plain | 239761 | `e4c9cfe84857bbb5d743ffdbdc330e07f960ec5eb980cbb73ef8233c681427a8` | 2026-09-11T06:17:20.345382453Z |

### Amendment `0001140361-26-035325`

| Artifact | Exact URL | HTTP | Content-Type | Bytes | SHA-256 | fetchedAt |
| --- | --- | ---: | --- | ---: | --- | --- |
| filing index | `https://www.sec.gov/Archives/edgar/data/320193/000114036126035325/0001140361-26-035325-index.htm` | 200 | text/html; charset=UTF-8 | 10309 | `c943a95499062d57f24e8d2258148420ff4fdd908b947a4d07b9c7d9797e5a30` | 2026-09-11T06:17:21.286675448Z |
| primary document | `https://www.sec.gov/Archives/edgar/data/320193/000114036126035325/ef20081427_8ka.htm` | 200 | text/html | 39807 | `4cc8ebccca4cd4870b6afd6b864225448a0cece5cc65478dc2947835567a26f7` | 2026-09-11T06:17:22.289045229Z |
| complete submission text | `https://www.sec.gov/Archives/edgar/data/320193/000114036126035325/0001140361-26-035325.txt` | 200 | text/plain | 241262 | `f5660c1dc72cb72de6c258f7db4df6c271367aecd4c482acaebadf38f907add3` | 2026-09-11T06:17:23.292990352Z |

source / provider: SEC Archives（`provider=SEC`）

raw payload は repository に大量保存していない（hash + URL + size を記録）。

## 6. fetchedAt / ingestedAt

PR #4 で確定した意味を維持:

1. HTTP 成功
2. body 受信完了
3. 必要な整合確認成功（filing index への accession 含有、complete text の `ACCESSION NUMBER` 一致）
4. その後に `clock()` → `fetchedAt`

historical `knownAt` とは別。acceptanceDateTime を CONFIRMED knownAt に昇格していない。

## 7. Original / Amendment relationship

| 項目 | 値 |
| --- | --- |
| grade | **LIKELY** |
| 根拠 | amendment 本文に `April 20, 2026`（original filingDate）および Original Form / amends 文言あり |
| 非根拠 | amendment 本文は original accession `0001140361-26-015711` を**明示引用していない** |
| 禁止事項 | form=`8-K`/`8-K/A` かつ reportDate 同一だけで CONFIRMED としない |

判定定義:

- **CONFIRMED**: filing 本文または SEC 一次情報から対象関係を明示確認できる（例: 相手 accession の明示）
- **LIKELY**: form / reportDate / 文脈上は対応しそうだが直接証拠不足
- **UNVERIFIED**: 対応関係を証明できない

本 PoC の pair は **LIKELY**。LIKELY を CONFIRMED として扱わない。

## 8. Provenance（最小）

各 artifact に記録:

- provider = SEC
- accessionNumber
- endpoint / archive URL
- fetchedAt（retrieval time）
- raw payload SHA-256
- artifact type
- content size（bytes）
- httpStatus / contentType / source

巨大な Provenance class 体系は作っていない。

## 9. Fail-Closed

成功扱いにしない:

- HTTP 4xx / 5xx
- empty body
- requested accession と complete submission 側 accession 不一致
- hash 未生成（成功パスでは必ず SHA-256）
- User-Agent 未設定
- relationship 証拠不足なのに CONFIRMED 扱い

mock / synthetic live fallback なし。

## 10. テスト（ネットワーク非依存）

`SecFilingArtifactFailClosedTest`（local HttpServer）:

1. valid accession artifact 取得 + provenance
2. HTTP 404
3. HTTP 500
4. empty body
5. requested accession と artifact 側 accession 不一致
6. body 変更で hash 変化
7. failed fetch では fetchedAt を打刻しない
8. original と amendment を別 accession として保持
9. amendment が original を上書きしない
10. relationship 証拠不足なら CONFIRMED にしない
11. fetchedAt clock は response body 書き込み後のみ

既存テストも含め回帰確認済み。

## 11. Validation

```bash
./gradlew --no-daemon clean build
./gradlew --no-daemon test --rerun-tasks
./gradlew --no-daemon secEdgarAccessionPoc   # live（SEC_EDGAR_USER_AGENT 必須）
```

- unit/local tests: **67** / failures 0
- live PoC: PASS（取得・版分離・LIKELY 判定まで）

## 12. Data Contract 修正要否

**今回必須修正なし。**  
accession / archive artifact の保有単位と third-party filer 注意は本 PoC 文書で固定。  
将来 Provider 本実装時に data-contract へ「accession = submission version id」「先頭 CIK ≠ SecurityId」を正式条項化するのが妥当。

## 13. 残 Critical / High

| 重要度 | 項目 |
| --- | --- |
| High | original↔amendment を CONFIRMED にするには、accession 明示または SEC 一次の対応表が必要 |
| High | acceptanceDateTime は引き続き historical knownAt として不足（PR #4 結論を維持） |
| High | third-party filer accession を Issuer/Security と混同しない運用ルールの本実装反映が未着手 |
| Medium | index.json 併記・exhibit 個別取得・履歴 submissions files 横断は未実施 |

## 14. 最終判定

| 観点 | 判定 | 理由 |
| --- | --- | --- |
| SE | PARTIAL | archive URL・accession 意味は公式+実測で確認。Provider 本実装ではない |
| Programmer | PASS | 最小 client/store/test/live runner。過剰抽象化なし |
| Data Integrity | PARTIAL | 版分離・hash・Fail-Closedは確認。関係は LIKELY 止まり。knownAt 未解決 |
| QA | PASS | local Fail-Closed + 既存回帰 + live 分離実行 |

**総合: PARTIAL**

取得できたことだけを PASS 理由にしない。  
本 PoC を財務値 PIT・knownAt 完全解決・戦略有効性の証明として扱ってはならない。

## 15. 次工程へ進めるか

**条件付きで可。**  
次に進むなら「accession 版管理を data-contract に正式化」または「関係 CONFIRMED 条件の厳密化」が妥当。  
CompanyFacts / XBRL 財務値 / Quality / QDR / Backtest へは、本結果だけでは進めるべきでない。
