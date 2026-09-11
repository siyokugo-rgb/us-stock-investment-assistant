# US Stock Investment Assistant

米国株の投資判断を支援する Android アプリを最終製品とする。  
**自動売買ソフトではない。** ユーザー自身が楽天証券で手動注文する。

本リポジトリの GitHub `main` 起点は `ed86d18f6005567b0dbf77d4254b0523aa47f83e`（Initial commit）である。

## 現在の開発段階

**Phase -1：成立性検証** と **Phase 0：仕様仮決め**。

Android UI、実データ API 接続、戦略実装は行わない。  
今回の成果物は、**Android SDK に依存しない最小の PIT-safe Kotlin/JVM core** である。

## 運用前提（仮決め）

| 項目 | 内容 |
| --- | --- |
| 実運用第一候補 | 楽天証券 |
| 初期想定資金 | 10,000円 |
| moomoo証券 | **利用候補から除外** |
| 自動発注 | しない |

将来提示したい情報（未実装）:

- BUY候補 / WATCH / SELL候補 / EXTEND候補
- 判断理由 / Quality / 配当情報 / Valuation / 保有損益

## 戦略について

主戦略候補は **Quality Dividend Rotation（QDR）** だが、**未検証仮説** である。  
「配当日前に買う」条件は、将来のバックテスト結果によって廃止する可能性がある。  
そのため QDR 固有条件は本 core に埋め込んでいない。

将来比較する候補（いずれも未実装）:

- Classic Dogs of the Dow
- Quality-Gated Dogs
- QDR
- Quality Mean Reversion
- Dividend Capture
- Market Benchmark

## 開発原則（優先順位）

1. 正しい仕様
2. 正しいデータ
3. Point-in-Time 整合性
4. 正しい実装
5. 検証可能性
6. 保守性
7. 追加機能

「動くこと」と「正しいこと」は別である。存在しない API・仕様・データ・クラスを推測で前提にしない。

## Point-in-Time は Critical

各データは少なくとも次を区別する。

| フィールド | 意味 |
| --- | --- |
| `effectiveAt` | データが意味上どの時点・日付・期間を表すか |
| `knownAt` | そのバージョンを根拠付きで利用可能になった時刻 |
| `ingestedAt` | 本システムがそのデータを取得した時刻 |
| `source` | 情報源 |

例:

- price → `tradingDate`
- dividend → `exDate`
- fundamentals → `fiscalPeriodEnd`
- identifier → `validFrom` / `validTo`

知識PIT:

- `decisionAt < knownAt` → 使用禁止
- `decisionAt >= knownAt` → 利用可能候補

保有PIT（その時点でシステムが実際に保有していた情報を再現する場合）:

- さらに `ingestedAt <= decisionAt` が必要

この2種類を混同しない。現在時刻や対象日の 00:00 を暗黙補完しない。

詳細は [`docs/phase-0-spec.md`](docs/phase-0-spec.md)。  
実データ接続前の Data Contract は [`docs/data-contract.md`](docs/data-contract.md)（取得可能データと、投資判断・バックテストへ使ってよいデータの区別を含む）。
## Survivorship Bias は未解決 Critical

将来の Quality 候補 Universe は次の和集合を予定する。

- S&P 500 Dividend Aristocrats
- Dividend Kings
- Nasdaq Dividend Achievers
- S&P 500 Quality

**現在の構成銘柄を過去バックテストへ流用してはならない。**  
構成銘柄の Point-in-Time 履歴は未整備であり、Survivorship Bias は Critical のまま残っている。

V1 では銀行・証券・保険・REIT を一般事業会社と同じ Quality 式では評価しない（Quality 自体は未実装・未定義）。

## Fail-Closed

将来の投資判断では、必要なデータが欠損・不正値・stale・PIT違反・Security対応不明・source conflict の場合、推測値・0・前日値・似た Ticker で補完せず **`DATA_INVALID` として投資判断対象外** にする。

今回 Decision Engine は実装しない。原則のみを固定する。

**データ取得失敗時に synthetic / mock market data へ切り替えて投資判断を続ける実装は禁止。**  
テスト用の固定 fixture は許可するが、本番データ取得失敗時の代替入力としては使わない。

## Security 識別

Ticker を恒久主キーにしない。内部識別子は `SecurityId`。  
識別子履歴は `SecurityIdentifier`（`TICKER` / `CIK` / `VENDOR_PERMANENT_ID`）として保持する。

- Ticker 変更後も同一 `SecurityId` なら同一 Security
- 同じ Ticker 文字列でも異なる `SecurityId` なら別 Security
- Ticker recycle を文字列だけで結合しない
- 最新版の暗黙選択、source conflict の自動解決は行わない

## 今回の実装範囲

- 単一 Gradle プロジェクト（Kotlin/JVM, JDK 17）
- 不変モデル: `SecurityId`, `SecurityIdentifier`, `DailyPrice`, `DividendEvent`, `FundamentalSnapshot`
- PIT utility: `isAvailableAt(decisionAt)` および候補集合 Query
- 識別子の銘柄別・時点別候補 Index（自動統合なし）
- 明白に不正な OHLC / 負の配当額の拒否
- 仕様文書と検証記録

`DailyPrice` は unadjusted OHLC のみ。  
**将来 adjusted close を実際の売買価格として使用してはならない。**

`DividendEvent` の `declarationDate` / `recordDate` / `paymentDate` は null を許容する。  
履歴上 `exDate` が存在することと、過去の `decisionAt` 時点でその配当予定を知っていたことは別である。

`FundamentalSnapshot` は提出済み財務資料のメタデータのみ。Revenue / FCF / Debt / ROIC 等は未定義のため持たない。  
不変条件: `filedAt <= knownAt <= ingestedAt`。

## 未実装範囲

- Android / Jetpack Compose
- 実データ API（SEC, Tiingo, Yahoo Finance 等）および HTTP 通信
- Database
- Quality Score / QDR / Dogs / その他戦略
- Backtest Engine
- News / FX 共通化 / 楽天証券 API / 自動発注
- AI / ML
- Corporate Action の完全実装
- Web dashboard / Node.js / Express / React / Vite
- SMA / RSI 等のテクニカル指標
- BUY / HOLD / SELL のルールベース判定
- synthetic / mock market fallback
- adjusted price
- 財務数値そのもの

## ビルド / テスト

JDK 17 が必要。Gradle Wrapper を使う。

```bash
./gradlew test
```

ビルドのみ:

```bash
./gradlew build
```

検証記録は [`docs/validation.md`](docs/validation.md)。  
テストは現在時刻・乱数・外部 API に依存しない固定 fixture を使う。

## Data Contract / SEC PoC

- Data Contract: [`docs/data-contract.md`](docs/data-contract.md)
- SEC EDGAR submissions metadata PoC: [`docs/sec-edgar-poc.md`](docs/sec-edgar-poc.md)

Data Contract と SEC PoC は、実データ取得・財務/価格/配当 PIT 成立・戦略有効性を証明しない。  
`acceptanceDateTime` は public availability の lower-bound evidence になり得るが、historical `knownAt` 単独使用は insufficient（conservative proxy ではない）。
