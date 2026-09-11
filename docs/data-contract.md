# Data Contract（Phase 0 → 実データ PoC 前）

対象リポジトリ: `siyokugo-rgb/us-stock-investment-assistant`  
基準 `main`: `8711a85e038fb49d9585f7170ad72b95ab05788a`（merge PR #2）  
前提ドキュメント: [`phase-0-spec.md`](phase-0-spec.md)、[`validation.md`](validation.md)  
前提コード（Source of Truth）: `SecurityId`, `SecurityIdentifier`, `SecurityIdentifierIndex`, `DailyPrice`, `DividendEvent`, `FundamentalSnapshot`, `Pit`, `PitQuery`

## 文書の位置づけ

本 Data Contract は、**外部 API 接続前**に、次を固定するための設計文書である。

- 何のデータを
- 何を意味する値として
- いつ利用可能だった情報として（`knownAt`）
- どの主体・証券・出典・訂正履歴に結び付けて扱うか

本契約は次を**証明しない**。

- 実データが取得可能であること
- 実データで PIT が成立すること
- 戦略が有効であること

### 取得できるデータ ≠ 投資判断・バックテストへ使ってよいデータ

| 区分 | 意味 |
| --- | --- |
| Retrievable | Provider / 文書から取得・保存できる生データ |
| Contract-valid | 本契約の型・意味・必須項目を満たす |
| PIT-safe at `decisionAt` | `knownAt <= decisionAt`（必要なら保有PITも） |
| Strategy-eligible | Data Quality が許容し、当該戦略が要求する品質を満たす |

戦略・バックテストへ渡してよいのは **Strategy-eligible** のみである。  
Retrievable であることだけを理由に Quality / Dogs / QDR / Backtest へ渡してはならない。

### 今回の範囲外（実装しない）

SEC / Tiingo / Yahoo 等の HTTP クライアント、DB、Android、戦略、バックテスト、Broker API、自動売買、AI/ML、Web dashboard、synthetic/mock market fallback、他プロジェクト由来の仕様。

楽天証券の手数料・端株・残高・ポイントは本契約に混入させない（§10）。

---

## 1. Issuer / Security

### 1.1 用語の区別

| 概念 | 意味 | 現状コード |
| --- | --- | --- |
| Issuer | 提出・発行の主体（会社等） | **未モデル化** |
| Security | 取引可能な証券（例: 普通株の1 share class） | `SecurityId` |
| CIK | SEC が付与する提出主体側識別子 | `IdentifierType.CIK` として `SecurityIdentifier` に載せ得るが、意味は Issuer 寄り |
| Ticker | 取引所表示用の一時的シンボル | `IdentifierType.TICKER` |
| Exchange | 上場市場（例: NYSE, NASDAQ） | **未モデル化**（識別子 value に埋め込まない） |
| Vendor Permanent Identifier | ベンダー固有の恒久ID | `IdentifierType.VENDOR_PERMANENT_ID` |

### 1.2 CIK と Security は 1:1 ではない

SEC CIK は**提出主体**側の識別子である。1 CIK に複数の share class / 証券がぶら下がることがある。  
逆に、ある株式 Security の開示が親会社 CIK 配下に載ることもある。

**結論: Issuer と Security は分離すべきである。**

- 投資判断・価格・配当・保有数量の主キーは **Security**（現行 `SecurityId`）
- 提出書類・CIK・issuer-level fundamentals の主キーは将来 **Issuer**（未実装）
- 同一 Issuer に複数 share class（例: 議決権差のある Class A / Class B）がある場合、**別 `SecurityId`** とする
- CIK 文字列だけで Security を一意確定してはならない。衝突・複数候補は Fail-Closed（`IDENTITY_UNRESOLVED`）

**今回は `IssuerId` クラスを新規実装しない。** Phase 0 core の `SecurityId` を維持し、Issuer 分離は次のモデル拡張候補として本契約に固定するに留める。

### 1.3 事象定義

| 事象 | 定義 | `SecurityId` | Identifier 履歴 |
| --- | --- | --- | --- |
| Ticker change | 同一 Security の表示シンボル変更 | **継続** | 旧 TICKER を `validTo` で閉じ、新 TICKER を新行で開始 |
| Ticker recycle | 過去に使われた Ticker 文字列が、別 Security に再割当 | **別 Security** | 文字列一致だけで結合禁止 |
| Exchange change | 同一 Security の主上場市場変更 | **継続**（原則） | Exchange は別属性。Ticker も変わる場合は ticker change と併用 |
| Delisting | 上場廃止 | **継続**（履歴参照用）だが取引対象外 | listing 状態は別契約。Ticker の `validTo` を閉じる |
| Merger / Acquisition | 存続・消滅・対価の組合せ | 消滅 Security は終了、存続または新 Security を明示 | 価格・株数・対価は Corporate Action（§5） |
| 同一 Ticker 文字列の別 Security | 同時または異時点で同文字列 | **別 `SecurityId`** | Index は候補をすべて返す |

### 1.4 `validFrom` / `validTo`

現行（維持）:

- `validFrom`: inclusive
- `validTo`: exclusive
- `validTo == null`: その版では終了日未知（最新の暗黙選択ではない）

**方針:** core 内部はこの半開区間を維持する。  
Provider が inclusive/inclusive 等で返す場合は、**Provider 境界アダプタで変換**し、変換規則と生値を provenance に残す。曖昧なら `IDENTITY_UNRESOLVED` / `INVALID_VALUE` とし推測補完しない。

---

## 2. Daily Price Contract

### 2.1 必須フィールド（現行 `DailyPrice`）

| フィールド | 意味 |
| --- | --- |
| `securityId` | 対象 Security |
| `tradingDate` | **対象取引所の取引日**（UTC 日付ではない） |
| `open` / `high` / `low` / `close` | 当該セッション定義に基づく未調整価格 |
| `volume` | 当該セッション定義に基づく出来高 |
| `currency` | 価格通貨 |
| `knownAt` | その版を根拠付きで利用可能になった時刻 |
| `ingestedAt` | 本システム取得時刻 |
| `source` | 情報源（Phase 0 は文字列。拡張は §7） |

### 2.2 取引日・セッション・確定

| 項目 | 契約上の扱い |
| --- | --- |
| exchange timezone | `tradingDate` は Security の主市場タイムゾーンにおける取引カレンダー日。UTC 日付への暗黙変換禁止 |
| regular session | Phase 1 PoC の日足は **regular session 公式終値ベース**を既定とする |
| pre-market / after-hours | 別シリーズ。regular 日足へ混在させない |
| 日足確定時刻 | Provider が「official close 確定」と主張する時刻。`knownAt` の候補根拠になるが、配信遅延を無視して session close 瞬間を `knownAt` にしてはならない |
| `knownAt` の根拠 | 「その OHLC 版を、根拠付きで決定に使えた最初の時刻」。ベンダー配信時刻・取引所公式確定・我々の受信時刻を混同しない。根拠を証明できないなら historical backtest の知識PITには使えない |
| 休場日 | 行を作らない。前日 close の持ち越し行を生成しない |
| trading halt | halt 中の部分足を通常確定日足として黙って採用しない。Provider がhalt印を出さない場合は品質リスクとして記録 |
| `volume = 0` | モデル上は許容（現行どおり）。休場と混同しない。Data Quality で文脈判定 |
| `OHLC = 0` | 下記 §2.3 |

### 2.3 0 価格の扱い（結論）

現行モデルは `price >= 0` を許容し、負のみ拒否する。

| 案 | 内容 |
| --- | --- |
| A | モデル構築時に reject |
| B | raw として保持し Data Quality 層で `INVALID_VALUE` |

**結論: B を採用する。**

理由:

1. 0 が「欠損の番兵」「halt/特殊表記」「不良配信」のいずれかを、モデル層だけでは区別できない。
2. Phase 0 仕様は `< 0` のみ拒否と既に整合している。
3. 投資判断へ渡す前に Quality で落とす方が、Fail-Closed と監査可能性が高い。

**コード変更は行わない**（契約確定前にモデルを変えない方針に従い、かつ現行は B と整合）。

正の異常値（例: 明らかな単位誤り）も、証明できない補正をせず Quality / conflict で扱う。

### 2.4 Adjusted と raw の境界

| データ | 用途 | 注文・約定価格 |
| --- | --- | --- |
| raw OHLC（現行 `DailyPrice`） | 執行・約定・指値・損益の基準候補 | 使用可（Broker 条件は別層） |
| adjusted OHLC | 長期リターン連続化の分析用 | **禁止** |
| adjusted volume | 分析用。定義を Provider 依存で明示 | 執行数量に使わない |
| split adjustment | Corporate Action と株数をセットで扱う | 価格だけ調整して株数を放置することは禁止 |
| dividend adjustment | total return 分析用 | 配当額の代替にしない |
| total return 系列 | ベンチマーク比較用 | 売買価格ではない |

**Adjusted price を実際の注文・約定価格として使用することは禁止。**  
Split 調整済み価格だけを使い、保有株数を逆調整しない等の不整合は禁止（§5）。

---

## 3. Dividend Contract

### 3.1 フィールド（現行 `DividendEvent`）

| フィールド | 必須 | 意味 |
| --- | --- | --- |
| `securityId` | yes | 対象 Security（share class） |
| `declarationDate` | no | 発表日。不明なら null |
| `exDate` | yes | 権利落ち日（effectiveAt） |
| `recordDate` | no | 権利確定日 |
| `paymentDate` | no | 支払日 |
| `amountPerShare` | yes | 1株あたり金額（負禁止） |
| `currency` | yes | 通貨 |
| `dividendType` | yes | 現行: `REGULAR` / `SPECIAL` / `UNKNOWN` |
| `knownAt` / `ingestedAt` / `source` | yes | PIT / 監査 |

**重要:** 履歴上 `exDate` が存在する ≠ 過去 `decisionAt` 時点で配当予定を知っていた。可用性は `knownAt`。

### 3.2 状態候補

| 状態 | 扱い |
| --- | --- |
| REGULAR | 現行 enum で表現 |
| SPECIAL | 現行 enum で表現 |
| UNKNOWN | 種別不明。推測で REGULAR にしない |
| CANCELLED | **将来拡張候補**。Provider が明示しない限り生成しない |
| CORRECTED | 状態というより **revision**（§8）。訂正後版を別レコード/版として保持 |

Provider から得られない CANCELLED / CORRECTED を推測生成しない。

### 3.3 配当 PIT 品質 Grade

| Grade | 定義 |
| --- | --- |
| A | 公式一次情報等で、発表時点（またはそれに相当する利用可能時刻）を確認できる |
| B | SEC filing / IR 等から「当時公表済みだったこと」を再構築できる |
| C | 現在の履歴（exDate/amount 一覧等）しかなく、公開時点を保証できない |

**採用する。** Grade は戦略適格性の入力であり、取得可否そのものではない。

**結論: 配当予定を事前に知る必要がある戦略（例: QDR の「配当日前に買う」、Dividend Capture）では Grade C を入力禁止とする。**  
exDate 経過後の事実確認のみに使う場合でも、知識PITが破れないことと Grade を分けて記録する。

QDR 自体は未検証仮説であり、本 Grade 定義は QDR 専用ではない（§11）。

---

## 4. Fundamental Data Contract

### 4.1 SEC EDGAR を主要一次情報候補とする

現行 `FundamentalSnapshot` はメタデータのみ（数値なし）。実データ PoC 前に意味を固定する。

| フィールド | 意味 | 現行 |
| --- | --- | --- |
| `issuerId` | 提出主体（将来） | 未実装 |
| `securityId` | 証券。share-class 固有指標がある場合に使用 | あり |
| CIK | Issuer 側識別子 | Identifier または将来 Issuer 属性 |
| `accessionNumber` | EDGAR 提出のaccession | 未実装（provenance 候補） |
| `formType` | 10-K / 10-Q / 8-K / 10-K/A 等 | 未実装 |
| `fiscalPeriodEnd` | 会計期間末日（effectiveAt） | `fiscalPeriodEnd` |
| `filedAt` | 提出時刻 | `filedAt` |
| `acceptedAt` | EDGAR accepted 時刻（filed と異なる場合あり） | 未実装 |
| `knownAt` | その版を根拠付きで利用可能になった時刻 | あり |
| `ingestedAt` | 取得時刻 | あり |
| `source` | 情報源 | あり |

例: `fiscalPeriodEnd = 2025-12-31`, `filedAt = 2026-02-20` → 2026-01-15 の decision では使用禁止。

### 4.2 版の区別（必須）

| 区分 | 意味 |
| --- | --- |
| original filing | 初回提出版 |
| amendment | 訂正提出（例: 10-K/A） |
| restatement | 後年の再陳述・修正後財務 |
| as-reported | ある `decisionAt` 時点で利用可能だった当時の数値 |
| later corrected value | 後日判明した訂正値 |
| vendor-normalized value | ベンダーが会計項目を正規化した値（一次情報ではない） |

**後日 10-K/A が出ても、その訂正値を過去 `decisionAt` へ遡及使用してはならない。**  
as-reported と restated を同一フィールドに上書きしない。版ごとに `knownAt` を持つ。

### 4.3 将来の数値メトリクス

Revenue / FCF / Debt / ROIC 等を追加するときは、少なくとも次を識別可能にする。

- metric definition
- currency
- unit
- fiscal period
- annual / quarterly / TTM
- consolidated scope
- taxonomy/tag
- as-reported / restated

**意味未定義の `Map<String, BigDecimal>` は禁止**（現行どおり）。

---

## 5. Corporate Action Contract（設計のみ・完全実装しない）

対象: stock split, reverse split, ticker change, merger, acquisition, spin-off, delisting。

| 事象 | SecurityId | 終了/新規 | effective date | knownAt | identifier | raw price | share qty | per-share dividend |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| stock split / reverse | 継続 | 終了しない | 分割効力日 | 発表/効力の利用可能時刻を分離 | 通常不変 | raw は分割前後で段差があり得る | **必ず同時調整** | 要すれば同時定義 |
| ticker change | 継続 | なし | 変更日 | 変更通知の利用可能時刻 | 旧/新 TICKER 履歴 | 不変 | 不変 | 不変 |
| merger / acquisition | 消滅側は終了、存続/新を明示 | 条件付き新規 | 効力日 | 公表・効力を分離 | 再割当 | 対価計算とセット | 対価比率とセット | 案件依存 |
| spin-off | 親は継続、子は新規 | 子を新規 | 効力日 | 同上 | 子に新 ID | 権利落ちと新株を対応 | 配分比率必須 | 場合により配当類似 |
| delisting | 継続（履歴） | 取引終了 | 廃止日 | 公表時刻 | Ticker を閉じる | 最終日足まで | 保有はそのまま（換金は実行層） | 以降は契約外 |

**価格だけ split-adjust して株数を調整しないことは禁止。**  
今回はモデル完全実装をしない。Identifier 履歴は入口に過ぎない。

---

## 6. Historical Universe Contract（Critical）

候補 Universe（和集合・いずれも未実装）:

- S&P 500 Dividend Aristocrats
- Dividend Kings
- Nasdaq Dividend Achievers
- S&P 500 Quality

**現在の構成銘柄を過去バックテストへ流用禁止。**

### 6.1 メンバーシップ行の候補フィールド

- `universeId`
- `securityId`
- `membershipFrom` / `membershipTo`（半開区間を推奨、Provider 境界で変換）
- `announcedAt`（発表時刻。分かる場合）
- `knownAt`（そのメンバーシップ版を利用可能になった時刻）
- `ingestedAt`
- `source`

### 6.2 方式比較

| 観点 | A: 実際の過去 membership 履歴を取得 | B: 当時利用可能だった財務・配当から採用条件を再構築 |
| --- | --- | --- |
| PIT精度 | 公式再構成に近ければ高い | 条件定義・入力データの PIT に依存。ズレやすい |
| Survivorship Bias | 履歴が完全なら抑制 | 条件再構築に失敗した脱落銘柄を落とすと残存バイアス |
| Look-ahead Bias | `knownAt`/`announcedAt` を誤ると発生 | 後知の財務確定値を使うと発生しやすい |
| データ入手性 | ベンダー/指数会社依存。欠けることあり | 配当・財務が揃えば試みられる |
| ライセンス | 指数・リストは制約が強いことが多い | 一次情報ベースなら相対的に明確な場合あり |
| 再現性 | 同一データベンダーに依存 | ルールを文書化すれば再現可能だが結果は公式と不一致し得る |
| 実装コスト | ETL + ライセンス | ルールエンジン + PIT 入力の品質依存 |

### 6.3 推奨方針

1. **S&P 系・Nasdaq Achievers 等、委員会/指数の公式構成があるもの → 方式 A を優先。** ライセンス可能な PIT membership が無いなら、その Universe を使った検証は **未成立（Critical 継続）** とし、現行構成の過去適用で代替しない。
2. **Dividend Kings → 「連続増配年数」条件の再構築（方式 B）を主候補とする。** ただし入力配当は §3 の Grade A/B のみ。Grade C 配当で年数を数えない。公式リストがライセンス付きで得られるなら A で照合し、A/B 不一致は conflict として自動採用しない。
3. 方式 B を使う場合でも「公式 Kings リストそのもの」と主張しない。**自前定義の連続増配セット**として明示する。

Survivorship Bias 対策の中核は、**評価日時点で membership/規則適格だった Security だけを入れる**ことである。現在リストのスナップショット逆適用は禁止。

---

## 7. Provenance / Source Contract

現行 `source: String` は Phase 0 core には十分だが、実データ監査には不足し得る。

### 7.1 将来追跡候補

- provider
- endpoint / document
- provider record id
- retrieval time（≈ `ingestedAt`）
- SEC accession
- raw payload hash
- schema/version
- request parameters

### 7.2 Phase 1 PoC の最小と拡張境界

Phase 1 PoC 最小:

- 既存の `source` 文字列を **provider 名または document 識別の安定トークン**に限定して使う
- 取得バッチごとに外部監査ログ（リポジトリ外でも可）へ retrieval time・request・payload hash を残す運用を許容
- **今回 Source クラス群は実装しない**

将来拡張境界:

- `source` 文字列のパースにビジネスロジックを詰め込まない
- payload 本体をドメインモデルに埋め込まない（hash / locator で参照）

---

## 8. Revision / Conflict Contract

| 種別 | 意味 | 自動解決 |
| --- | --- | --- |
| duplicate | 同一版の重複取り込み | 除外は可だが、異版と決めつけない |
| revision | Provider 同一キーの新しい版 | 版を並存。最新自動採用禁止 |
| correction | 誤り訂正 | 新版の `knownAt` 以降のみ使用可。過去へ遡及禁止 |
| superseded | 明示的に旧版失效 | フラグがあるときのみ。推測禁止 |
| source conflict | Provider 間で値不一致 | **自動解決禁止** |

禁止例:

- 新しい方を自動採用
- SEC だから常に採用
- 平均値にする

解決不能なら `SOURCE_CONFLICT` / `DATA_INVALID` として戦略へ渡さない。

---

## 9. Data Quality Contract

投資シグナル（BUY / SELL / WATCH 等）とは**完全に別責務**。

| コード | 意味 |
| --- | --- |
| VALID | 契約・PIT・同一性・非conflict を満たす |
| MISSING_REQUIRED | 必須欠落 |
| PIT_UNSAFE | `decisionAt < knownAt` 等 |
| STALE | 許容遅れ超過（閾値は未定義・PoC で設定） |
| IDENTITY_UNRESOLVED | Security/Issuer 対応が候補複数または不能 |
| SOURCE_CONFLICT | ソース間不一致 |
| INVALID_VALUE | 0 価格の運用拒否、不正 OHLC など |

追加候補（必要時）: `REVISION_AMBIGUOUS`, `GRADE_INSUFFICIENT`, `MEMBERSHIP_UNKNOWN`, `CORPORATE_ACTION_UNAPPLIED`。

必要入力が VALID（かつ戦略別の追加制約）でなければ、Quality / Dogs / QDR / Backtest へ渡さない。

---

## 10. 楽天証券との境界

- 実運用第一候補: 楽天証券
- 初期想定資金: 10,000円
- moomoo: 候補除外（製品方針。本契約のフィールドには載せない）

本 Data Contract に混入させないもの:

- 楽天証券手数料
- 1株/端株条件
- 残高
- 楽天ポイント

これらは将来 **Execution Feasibility / Portfolio / Cost Model** で扱う。  
**Strategy Signal と Execution 可能性は別判定**である。

---

## 11. QDR との境界

QDR は未検証仮説である。「配当日前に買う」条件が将来廃止されても、本 Data Contract はそのまま再利用可能でなければならない。

共通利用対象:

- Classic Dogs of the Dow
- Quality-Gated Dogs
- Quality Mean Reversion
- Dividend Capture
- Market Benchmark
- QDR（採用・廃止どちらでも）

戦略固有のエントリー条件を、価格・配当・財務・Universe の定義へ埋め込まない。

---

## 12. 必須 QA ケース（具体例）

1. **fiscalPeriodEnd より filedAt が後**  
   `fiscalPeriodEnd=2025-12-31`, `filedAt=2026-02-20` → `decisionAt=2026-01-15T15:00:00Z` では PIT_UNSAFE。期末日が過去でも提出前は不可。

2. **amendment が後から出る**  
   10-K（knownAt=T1）と 10-K/A（knownAt=T2>T1）。`decisionAt` が T1..T2 なら as-reported の原版のみ。T2 以降でも、戦略が as-reported を要求するなら原版を使い、訂正版を黙って上書きしない。

3. **declarationDate 不明の配当**  
   `declarationDate=null` は保持可。ただし事前察知戦略では Grade C（または発表時刻不明）として入力禁止になり得る。exDate だけの履歴を Grade A 扱いにしない。

4. **ticker change**  
   同一 `SecurityId` で OLD→NEW。`validTo` exclusive で旧を閉じる。価格系列は同一 Security に残す。

5. **ticker recycle**  
   2010-2018 に Security A が `RECY`、2020- に Security B が `RECY`。文字列一致で結合しない。2020-06 の候補は B のみ。

6. **stock split**  
   2-for-1 の raw close が半減しても、保有株数を×2しない分析は禁止。adjusted 系列だけ見て約定価格にしない。

7. **delisted security**  
   廃止後も履歴参照用に `SecurityId` は残す。Universe membership と listing 状態を混同しない。廃止後の偽価格行を生成しない。

8. **2026年 Universe を 2020年バックテストへ使用禁止**  
   2026-09 時点の Aristocrats リストを 2020-01 のユニバースにしてはならない。Survivorship Bias。

9. **adjusted close を実売買価格へ使用禁止**  
   分析用 total return と、楽天証券での指値/成行の基準価格を混同しない。

10. **source conflict を自動解決しない**  
    Provider X close=10.00、Y close=10.20。新しい方・SEC・平均での自動採用禁止。SOURCE_CONFLICT。

11. **同一 Issuer に複数 share class**  
    Class A と Class B は別 `SecurityId`。同一 CIK でも価格・配当・議決権が異なり得る。CIK 一致だけで1銘柄に潰さない。

12. **ingestedAt は遅いが信頼可能な historical knownAt を持つ**  
    2024-06-10 に公表・配信された価格を 2026-09 にアーカイブ取得しても、知識PITの `knownAt` は 2024-06-10 側の根拠時刻。`ingestedAt=2026-09-...`。保有PIT再現には ingestedAt も見る。二者を入れ替えない。

---

## 13. 既存モデル変更の要否

| 項目 | 要否 | 理由 |
| --- | --- | --- |
| Issuer モデル追加 | 今回不要 | 分離結論は契約に固定。実装は PoC 設計後 |
| 0 価格 reject 化 | 不要 | 結論 B。現行許容と一致 |
| CANCELLED 配当 enum | 今回不要 | Provider 明示まで推測追加しない |
| Source 構造体 | 今回不要 | Phase 1 は文字列 + 外部監査ログで可 |
| Universe / CorporateAction 実装 | 今回不要 | 契約のみ |

Critical / High の意味的バグとして、現行コードを直ちに壊す欠陥は検出していない（負価格拒否・PIT分離・Ticker 非主キーは契約と整合）。

---

## 14. 残課題と PoC 方針

### Critical（残）

1. Historical membership の入手・ライセンス未確定（Survivorship）
2. `knownAt` の根拠を実 Provider で証明していない
3. Issuer/Security 分離が未実装（設計のみ）
4. Corporate Action 未実装
5. Quality 定義なし / QDR 未検証

### High（残）

1. stale 閾値未定義
2. Exchange / session カレンダー未接続
3. acceptedAt と filedAt の使い分け未実装
4. 配当 Grade の判定手続きが未運用
5. 0 価格を Quality で落とす実装が未着手（契約のみ）

### 実データ PoC へ進めるか

**条件付きで進めてよい。** ただし本契約の存在は「取得可能・PIT成立・戦略有効」の証明ではない。

最初の PoC Source 推奨: **SEC EDGAR（提出メタデータ: CIK, accession, formType, filed/accepted）**  
理由: 一次情報に近く、amendment と as-reported の版管理を先に検証できる。価格・Universe はライセンスと `knownAt` 根拠が揃ってから。

価格 PoC を併せる場合も、Yahoo Finance を権威源にしない。adjusted を執行価格に使わない。取得失敗時の mock fallback 禁止。

### SEC EDGAR submissions PoC で確定した限界（2026-09-11）

実施記録: [`sec-edgar-poc.md`](sec-edgar-poc.md)

- 同一 CIK に複数 Ticker / share class がぶら下がる（例: Alphabet）。**Issuer と Security の分離は実データ上必要。**
- `acceptanceDateTime` は EDGAR acceptance 時刻であり、sec.gov 初回 public dissemination 時刻の CONFIRMED 根拠にはならない。historical `knownAt` 評価は **PARTIAL**。
- `filingDate` は日付のみ（PARTIAL）。`reportDate` は公開時刻ではない（UNUSABLE）。`ingestedAt` を historical knownAt 代理にしてはならない。
- amendment（例: 8-K と 8-K/A）は別 accession / 別 acceptance として保持する。合成 amendment で PASS 扱いにしない。
- submissions メタデータ取得成功 ≠ 財務値 PIT・価格 PIT・配当 PIT・戦略有効の証明。
