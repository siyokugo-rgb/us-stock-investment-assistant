# Phase 0 仕様（仮決め）

対象: 米国株投資判断支援アプリの Kotlin/JVM core。  
最終製品は Android。自動売買ではない。ユーザーが楽天証券で手動注文する。

段階: **Phase -1 成立性検証** + **Phase 0 仕様仮決め**。  
本ドキュメントは仮決めであり、Data Contract 設計・実データ検証の前に変更され得る。

起点 commit: `ed86d18f6005567b0dbf77d4254b0523aa47f83e`。

---

## 1. Point-in-Time

PIT は Critical 要件である。「その日付の価格がある」ことと「その時刻にその情報を使ってよかった」ことは別である。

### 1.1 時刻・日付フィールド

| 名前 | 型の目安 | 意味 |
| --- | --- | --- |
| effectiveAt | モデルごとに `LocalDate` 等 | データが意味上どの時点・日付・期間を表すか |
| knownAt | `Instant` | そのバージョンの情報を、根拠付きで利用可能になった時刻 |
| ingestedAt | `Instant` | 本システムがそのデータを取得した時刻 |
| source | 非空文字列 | 情報源 |

effectiveAt の対応:

| モデル | effectiveAt |
| --- | --- |
| DailyPrice | `tradingDate` |
| DividendEvent | `exDate` |
| FundamentalSnapshot | `fiscalPeriodEnd` |
| SecurityIdentifier | `validFrom` / `validTo` |

`validFrom` / `validTo` と `knownAt` は別の意味である。  
識別子がいつからいつまでその Security にひも付いていたかと、その対応をいつ知ったかは独立する。

### 1.2 知識PITと保有PIT

知識PIT（実装: `Pit.isAvailableAt` / 各モデルの `isAvailableAt(decisionAt)`）:

- `decisionAt < knownAt` → 使用禁止（unavailable）
- `decisionAt >= knownAt` → 利用可能候補（available）

保有PIT（実装: `Pit.wasHeldBySystemAt`）:

- その時点でシステムが実際に保有していた情報を再現する場合は `ingestedAt <= decisionAt` も必要
- 知識PITと混同しない。`isAvailableAt` は `ingestedAt` を見ない

現在時刻や対象日の 00:00 への暗黙変換は禁止する。`LocalDate` と `Instant` は別値として明示する。

### 1.3 配当の PIT

履歴上 `exDate` が存在することと、過去の `decisionAt` 時点でその配当予定を知っていたことは別である。

例: 事後に判明した配当を、判明前の decision に使ってはならない。可用性は `knownAt` で判定する。

### 1.4 財務の PIT

`FundamentalSnapshot` は提出済み財務資料を表す。

- `fiscalPeriodEnd`: 対象期間の終了日
- `filedAt`: 提出時刻
- `knownAt`: その版を根拠付きで利用可能になった時刻

例: `fiscalPeriodEnd = 2025-12-31`, `filedAt = 2026-02-20` なら、2026-01-15 の decision では使用禁止。

不変条件: `filedAt <= knownAt <= ingestedAt`。

Revenue / FCF / Debt / ROIC 等は、意味・単位・通貨・対象期間・annual/quarterly/TTM・consolidated scope・as-reported/restated を定義するまで追加しない。意味未定義の `Map<String, BigDecimal>` は禁止。

### 1.5 価格の PIT と adjusted close

`DailyPrice` の effectiveAt は `tradingDate`。可用性は `knownAt`。

Adjusted close は今回実装しない。  
**将来 adjusted close を実際の売買価格として使用してはならない。**  
分割・配当調整済み系列はリターン分析等と執行価格を混同しやすい。執行・損益の基準価格は未調整の市場価格側で別途定義する。

---

## 2. Security 識別

Ticker を恒久主キーにしてはならない。Ticker は変更され、後年別会社に再利用され得る。

### 2.1 SecurityId

Ticker から導出しない内部識別子。同一銘柄の履歴は同一 `SecurityId` でつなぐ。

### 2.2 SecurityIdentifier

最低限の保持項目:

- securityId
- type
- value
- validFrom
- validTo
- knownAt
- ingestedAt
- source

identifier type（最低限）:

- `TICKER`
- `CIK`
- `VENDOR_PERMANENT_ID`

### 2.3 妥当期間

Phase 0 の仮決め:

- `validFrom` は inclusive
- `validTo` は exclusive
- `validTo == null` は、そのバージョンでは終了日が未知であること（「最新」の暗黙選択ではない）

Ticker 変更日に旧識別子と新識別子が両方 valid にならないようにするため、exclusive end を選んだ。  
この規則は Data Contract でベンダー日付の inclusive/exclusive が判明したとき再確認する。

### 2.4 結合規則（禁止事項）

- 同じ Ticker 文字列だけを理由に同一 Security として結合しない
- 最新版を自動選択しない
- source conflict を自動解決しない
- Ticker 文字列だけで recycle を同一銘柄にしない

`SecurityIdentifierIndex` は、銘柄＋時点、または type+value＋時点で **候補集合** を返す。  
衝突（同一 Ticker が複数 SecurityId にヒット等）は呼び出し側が Fail-Closed する。

Ticker 変更後も同一 `SecurityId` なら同一 Security。  
同じ Ticker 文字列でも異なる `SecurityId` なら別 Security。

---

## 3. Fail-Closed

将来の投資判断で、必要なデータが次のいずれかの場合、補完せず判断対象外とする。

- 欠損
- 不正値
- stale
- PIT 違反
- Security 対応不明
- source conflict

禁止する補完:

- 推測値
- 0 埋め
- 前日値の持ち越し
- 似た Ticker への読み替え
- **データ取得失敗時の synthetic / mock market data への切り替え**

テスト用の固定 fixture は許可する。本番取得失敗の代替入力としては使わない。

今回 Decision Engine は実装しない。モデル層では明白に不正な値を構築不可にする。  
0 価格や 0 配当はモデルとしては拒否しない（仕様が `< 0` のみ拒否のため）。将来の判断層が `DATA_INVALID` にするかは未決。

---

## 4. QDR は未検証仮説

Quality Dividend Rotation は主戦略**候補**であり、成立性は未検証である。  
「配当日前に買う」条件を基盤へ埋め込まない。

将来比較する戦略（未実装）:

- Classic Dogs of the Dow
- Quality-Gated Dogs
- QDR
- Quality Mean Reversion
- Dividend Capture
- Market Benchmark

今回は戦略もバックテストエンジンも実装しない。

---

## 5. Survivorship Bias

将来の Quality 候補 Universe（和集合、未実装）:

- S&P 500 Dividend Aristocrats
- Dividend Kings
- Nasdaq Dividend Achievers
- S&P 500 Quality

**現在の構成銘柄を過去バックテストへ流用してはならない。**  
過去時点の構成に含まれていなかった銘柄を入れる、または当時含まれていたが現在落ちた銘柄を落とすと、結果が歪む。

構成の Point-in-Time 履歴が無い限り、Universe を用いた検証は成立しない。  
これは未解決 Critical issue である。

V1 では銀行・証券・保険・REIT を一般事業会社と同じ Quality 式では評価しない。Quality 式自体は未定義。

---

## 6. モデル不変条件（Phase 0）

共通:

- 金額は `BigDecimal`
- 日付は `LocalDate`
- 時刻は `Instant`
- 不変 data class
- `knownAt <= ingestedAt`
- `source` は非空

DailyPrice:

- `open` / `high` / `low` / `close` は負でない
- `volume` は負でない（`Long`）
- `high >= low`（数値比較。scale の違いは `compareTo`）
- `open` と `close` は `[low, high]` 内

DividendEvent:

- `exDate` は必須
- `declarationDate` / `recordDate` / `paymentDate` は null 可
- `amountPerShare` は負でない
- `dividendType`: `REGULAR` / `SPECIAL` / `UNKNOWN`

FundamentalSnapshot:

- 財務数値フィールドなし
- `filedAt <= knownAt <= ingestedAt`

---

## 7. 現在の未解決 Issue

### Critical

1. **Survivorship Bias** — 候補 Universe の Point-in-Time 構成履歴がない。
2. **Data Contract 未設計** — `knownAt` の根拠（開示時刻、ベンダー配信時刻、公式発表時刻）が未定義。モデルはあるが実データの正しさは未証明。
3. **Security 横断突合** — CIK / Ticker / vendor id の衝突時にどう Fail-Closed するかの運用規則が未確定。自動解決はしないと決めただけである。
4. **Corporate Action** — 分割、合併、Ticker 変更の完全な履歴モデルがない。Identifier 履歴はその入口に過ぎない。
5. **Quality 未定義** — 銀行・証券・保険・REIT を含む評価式が無い。QDR は仮説のまま。

### High

1. adjusted close を執行価格に使わない方針は文書化した。代替のリターン計算法は未定義。
2. 初期資金 10,000円に対する FX（USD/JPY）、端株、手数料、最小約定単位は未定義。
3. `validTo` exclusive は仮決め。ベンダー日付境界と不一致の可能性。
4. stale の定義（取引所カレンダー、配信遅延）が未定義。今回 stale 判定は未実装。
5. 0 価格・0 配当を判断層で `DATA_INVALID` にするかは未決。
6. 実運用ブローカーは楽天証券が第一候補だが、注文・残高のデータ契約は無い。

### スコープ外（意図的）

Android UI、HTTP、DB、戦略、バックテスト、ニュース、自動発注、AI/ML、Web dashboard、テクニカル指標、mock fallback。

---

## 8. 次工程

テスト PASS は、戦略や実データ取得の正しさを証明しない。  
次に進めるのは **Data Contract 設計**（情報源、フィールド意味、`knownAt` の根拠、Universe の PIT 履歴の要否）である。  
実 API 接続や戦略実装はその後である。
