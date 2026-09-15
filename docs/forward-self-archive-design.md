# Forward Self-Archive Operational Design

**文書種別:** 運用設計のみ（実装・client・DB・Backtest・Strategy・Android は対象外）  
**設計日 (UTC):** 2026-09-14  
**Repository:** `siyokugo-rgb/us-stock-investment-assistant`  
**Source of Truth:** GitHub `main`  
**Baseline SHA:** `f1d21e386044a7deca1ce77807dc2ee844832c89`  
**関連契約:** [`data-contract.md`](data-contract.md)、[`security-master-acceptance-criteria.md`](security-master-acceptance-criteria.md)、[`price-source-entitlement-review.md`](price-source-entitlement-review.md)、[`feasibility-gate-review.md`](feasibility-gate-review.md)、[`corporate-action-split-poc.md`](corporate-action-split-poc.md)

本文書は、Security Master / Price / Corporate Action / Dividend 等について、**今後取得するデータ**を possession evidence 付きで保存し、将来の **forward validation** に使うための最小運用を定義する。

---

## 0. 最終判定（先出し）

| Gate | 判定 |
| --- | --- |
| **Forward Research（設計ゲート）** | **CONDITIONAL GO** — 本設計の境界を守る限り、archive 開始以降の forward 検証準備を開始してよい |
| **Forward Research（実装・本番データ利用）** | 別ゲート。Provider client / entitlement / 手管理 `SecurityId` 紐付けが揃うまでデータは **Retrievable ≠ Strategy-eligible** |
| **Real Backtest** | **NO-GO**（維持） |

**Real Backtest NO-GO を維持する既存 blocker（本設計では解消しない）:**

- Price historical `knownAt`
- Corporate Action PIT（`announcedAt` ≠ `knownAt` ≠ `fetchedAt`）
- Universe entitlement
- Dividend Grade A/B
- Fundamentals `knownAt`
- Retrospective Security Master（provider historical `knownAt` 欠如）

---

## 1. 目的と非目的

### 1.1 目的

1. 取得成功した raw を **immutable** に残す  
2. 各観測に **possession evidence**（いつ取得し、いつ取り込み検証したか）を付ける  
3. `decisionAt` が **forward eligibility boundary** 以降のときだけ、将来の forward validation に使える候補にする  
4. revision / duplicate / missing / provider failure を観測可能な形で残す  

### 1.2 非目的（禁止）

| 禁止 | 理由 |
| --- | --- |
| `fetchedAt` / `ingestedAt` を historical `knownAt` 扱い | 契約違反・look-ahead の偽装 |
| retrospective backtest への遡及利用 | archive 開始前の as-known は再構成不能 |
| Backtest / Strategy / Android UI 実装 | 今回スコープ外 |
| DB 過剰設計（正規化スキーマ大量追加） | 最小運用に不要 |
| Provider 大量追加 | 既存候補の最小集合で足りる |
| ticker 文字列だけの identity 結合 | Acceptance / Data Contract 禁止 |
| currency / share class / issuer の推測補完 | Fail-Closed 維持 |

---

## 2. 用語（契約整合）

| 用語 | 意味 | 本設計での扱い |
| --- | --- | --- |
| **provider historical `knownAt`** | その版を根拠付きで決定に使えた時刻（provider / 一次公表） | self-archive では **生成しない**。無いなら retrospective PASS 不可 |
| **`fetchedAt`** | provider から **response body を完全受信した時刻**（成功 possession の開始証拠） | possession evidence。不完全受信・transport failure・MISSING では **null** |
| **`attemptedAt` / `attemptFinishedAt`** | 取得試行の開始／終了時刻（失敗・欠測含む） | `fetchedAt` とは別概念。試行ログ用 |
| **`ingestedAt`** | raw 保存・必須検証・ハッシュ確定（または欠測/失敗確定）が完了した時刻 | possession / 運用確定の証拠（保有PIT）。historical `knownAt` ではない |
| **`rawPayloadHash`** | provider から完全受信した **元 response body bytes** の SHA-256 | 圧縮後オブジェクトの hash ではない |
| **`storageObjectHash`** | 圧縮・暗号化など **保存オブジェクト** bytes の hash（任意） | `rawPayloadHash` と混同禁止 |
| **validity period** | 現実世界で事実が成り立つ期間（`validFrom`/`validTo` 等） | observed なら記録。無いなら UNKNOWN。`knownAt` に転用禁止 |
| **forward decision eligibility boundary** | その観測を **forward** 決定に使ってよい最早 `decisionAt` | 通常 `ingestedAt`（検証完了）を下限。より保守的な cutoff があれば max |
| **`coverageStartAt(domain, source)` / `coverageThroughAt(domain, source)`** | 当該 domain×source の forward coverage 境界 | global operational start とは別 |
| **global operational start** | 運用ジョブを開始した日時などの運用メタ | coverage と同義にしない |
| **possession evidence** | 「その時点で自システムが raw を保持・検証済みだった」証拠 | `fetchedAt`/`ingestedAt`/`rawPayloadHash`/source |
| **Retrievable** | 取得・保存できる | 本設計の主成果 |
| **Strategy-eligible** | 戦略・バックテストへ渡してよい | **本設計だけでは到達しない** |

**不変式（維持）:**

```text
fetchedAt ≠ historical knownAt
ingestedAt ≠ historical knownAt
attemptedAt / attemptFinishedAt ≠ historical knownAt
rawPayloadHash ≠ storageObjectHash（意味が異なる）
validFrom / listingDate / delistingDate / API asOf / updatedAt ≠ historical knownAt
eligibilityBoundary は forward 専用。retrospective へ転用禁止
coverageStartAt(domain, source) ≠ global operational start
```

Data Contract の「`ingestedAt` は知識PIT条件に含めない」と整合する。  
本設計の eligibility は **forward-only の保有下限**であり、provider `knownAt` の代替ではない。

---

## 3. 対象ドメイン（最小）

| Domain | archive 対象の例 | 備考 |
| --- | --- | --- |
| **Security Master / reference** | ticker reference、FIGI mapping、exchange/currency fields、active/delisted flags | ticker→`SecurityId` 禁止。手管理 mapping は別ノート |
| **Price** | raw daily OHLCV（adjusted と分離）、series meta | currency 無ければ DailyPrice 未到達 |
| **Corporate Action** | split / 将来の CA 生イベント | `announcedAt`/`effective`/`fetchedAt` 分離維持 |
| **Dividend** | 配当イベント raw | Grade C のまま decision input にしない |

同一運用フレームを共有し、ドメイン固有の MUST は各 PoC / Acceptance を優先する。

---

## 4. 観測レコード（論理最小形）

実装言語・永続化方式は未決定。論理レコードとして最低限次を持つ。

| フィールド | 必須 | 定義 |
| --- | --- | --- |
| `archiveId` | yes | 観測の不変 ID（UUID 等）。内容から推測しない |
| `domain` | yes | `SECURITY_MASTER` / `PRICE` / `CORPORATE_ACTION` / `DIVIDEND` / … |
| `source` | yes | provider + endpoint/product 名（例: `openfigi.mapping`, `eodhd.exchange-symbol-list`） |
| `requestKey` | yes | 再現可能なリクエスト識別（path/query/body の正規化表現）。秘密は含めない |
| `externalIdentifier` | conditional | namespace + value（例: `figi:BBG...`, `share_class_figi:...`, `isin:...`）。無ければ `UNKNOWN` とし ticker-only で埋めない |
| `externalIdentifierNamespace` | conditional | namespace 明示 |
| `asOfParam` | no | API の as-of / date パラメータ（**≠ knownAt**） |
| `observedFields` | yes | 観測できた論理フィールド集合（下記 §7） |
| `validityPeriod` | no | `validFrom`/`validTo` または listing/delisting 等。無い・曖昧なら null + `UNKNOWN` |
| `attemptedAt` | yes | UTC Instant。当該観測に対応する取得試行の開始時刻 |
| `attemptFinishedAt` | yes | UTC Instant。当該試行の終了時刻（成功・失敗・欠測いずれも） |
| `fetchedAt` | status 依存（§4.1） | UTC Instant。**response body 完全受信時のみ**非 null。試行時刻ではない |
| `ingestedAt` | status 依存（§4.1） | UTC Instant。検証・hash・immutable 保存（または欠損/失敗確定）完了時刻 |
| `rawPayloadHash` | status 依存（§4.1） | provider から完全受信した **元 response body bytes** の SHA-256 hex |
| `storageObjectHash` | no | 圧縮・暗号化後など **保存オブジェクト bytes** の hash（任意）。`rawPayloadHash` と別 |
| `rawPayloadUri` | status 依存（§4.1） | immutable raw（または保存オブジェクト）への参照。内容は書き換えない |
| `contentType` | status 依存 | 例: `application/json`。raw 無しなら null |
| `httpStatus` / `transportStatus` | yes | 成功時も記録。失敗は §11 |
| `revisionCandidateOf` | no（該当時のみ） | 先行 `archiveId` への **候補リンク**（同一論理キー・異なる hash）。権威ある置換を意味しない |
| `relatedPriorObservationIds` | no | 関連する先行観測 ID の集合（任意）。自動置換禁止 |
| `duplicateOf` | no（該当時のみ） | 同一 `rawPayloadHash` の既存 `archiveId` |
| `observationStatus` | yes | `OBSERVED` / `MISSING` / `PROVIDER_FAILURE` / `REJECTED_VALIDATION` |
| `eligibilityBoundaryAt` | status 依存（§4.1） | forward 用下限 Instant（§14）。MISSING/FAILURE は null |
| `notes` | no | 人手注記。自動で knownAt を書かない |

### 4.1 status 別 nullable（確定）

| フィールド | `OBSERVED` | `REJECTED_VALIDATION` | `MISSING` | `PROVIDER_FAILURE`（transport 含む） |
| --- | --- | --- | --- | --- |
| `attemptedAt` / `attemptFinishedAt` | **required** | **required** | **required** | **required** |
| `fetchedAt` | **required**（body 完全受信） | **required**（body 完全受信後に検証失敗） | **null** | **null**（body 未完全受信） |
| `ingestedAt` | **required**（検証・hash・保存完了） | **required**（検証終了） | **required**（欠損確定） | **required**（失敗確定） |
| `rawPayloadHash` | **required**（元 body bytes） | **required**（元 body bytes） | **null** | **null** |
| `storageObjectHash` | optional | optional | **null** | **null** |
| `rawPayloadUri` | **required** | **required** | **null** | **null** |
| `eligibilityBoundaryAt` | **required** | **null**（決定根拠に使わない） | **null** | **null** |
| `revisionCandidateOf` / `relatedPriorObservationIds` | optional | optional | **null** | **null** |
| `duplicateOf` | optional | optional | **null** | **null** |

共通制約（値が非 null のとき）:

- `attemptFinishedAt >= attemptedAt`
- `fetchedAt` があるなら `attemptFinishedAt >= fetchedAt >= attemptedAt`
- `ingestedAt >= attemptFinishedAt`（欠測/失敗確定を含む）
- `fetchedAt` があるときのみ `ingestedAt >= fetchedAt` を要求
- `fetchedAt` / `ingestedAt` / `attemptedAt` / `attemptFinishedAt` は historical `knownAt` ではない
- `rawPayloadHash` は常に **元 response body bytes** の SHA-256。圧縮後 hash ではない
- `storageObjectHash` は保存表現の完全性用（任意）。eligibility / identity の主ハッシュにしない
- `rawPayloadHash` があるなら対応 raw（またはそれから一意復元可能な保存物）は write-once

**禁止フィールド運用:**

- `knownAt` を self-archive 成功時に自動セットしない  
- `revisionCandidateOf` を authoritative replacement / latest-wins として扱わない  
- provider が publication timestamp を明示し、かつ別監査で CONFIRMED と判定できる場合のみ、**別フィールド**として記録候補にする（本設計のデフォルト対象外）

---

## 5. 時刻フィールドの詳細

### 5.1 `fetchedAt`

- **定義:** provider から response body を **完全受信した時刻**のみ非 null  
- `OBSERVED` / `REJECTED_VALIDATION`: body 完全受信後にセット（後者が検証失敗でも、受信自体は完了している）  
- `MISSING` / transport 失敗を含む `PROVIDER_FAILURE`: **必ず null**  
- 失敗・試行の時刻は `attemptedAt` / `attemptFinishedAt` に記録し、`fetchedAt` に入れない  
- `fetchedAt ≠ historical knownAt`（維持）  
- timezone: **常に UTC Instant** で保存。表示変換は後段  

### 5.2 `attemptedAt` / `attemptFinishedAt`

- すべての status で required  
- リトライがある場合: 当該観測レコードに対応する最終試行（またはポリシーで選んだ代表試行）の開始／終了  
- 成功 possession の証拠には使わない（それは `fetchedAt`）  

### 5.3 `ingestedAt`

- `OBSERVED`: 次がすべて完了した時刻  
  1. raw（元 body）を immutable 領域へ書き込み（必要なら圧縮保存は別表現）  
  2. `rawPayloadHash`（元 body bytes）再計算一致  
  3. 最小スキーマ検証（JSON parse 可、必須 envelope 等）  
- `REJECTED_VALIDATION`: parse/検証失敗でも raw は残し、検証終了時刻を `ingestedAt` とする  
- `MISSING` / `PROVIDER_FAILURE`: 欠損または失敗レコードを manifest に確定した時刻  

### 5.4 clock / timezone

| 規則 | 内容 |
| --- | --- |
| 保存 | UTC (`Instant`) |
| ソース時計 | OS/NTP 同期を前提。ずれ疑義は `notes` と運用インシデント |
| 市場カレンダー日付 | `America/New_York` 等の **session date** は observed field として別記。Instant と混同しない |
| provider local time 文字列 | 生値を raw に残し、変換規則を provenance に残す。曖昧なら UNKNOWN |

**禁止:** LocalDate 00:00 を `fetchedAt`/`ingestedAt`/`attemptedAt`/`knownAt` に自動変換。

---

## 6. source / external identifier

### 6.1 `source`

最小命名: `{vendor}.{product_or_endpoint}`  
例:

- `openfigi.v3.mapping`
- `massive.v3.reference.tickers`
- `eodhd.exchange-symbol-list`
- `tiingo.daily.meta`

vendor を増やしすぎない。既存 PoC / feasibility で触れた候補に限定。

### 6.2 `externalIdentifier`

| 規則 | 内容 |
| --- | --- |
| namespace 必須 | `figi`, `composite_figi`, `share_class_figi`, `isin`, `vendor_permanent_id`, … |
| ticker | **単独の externalIdentifier にしてはならない**（observed field としては可） |
| 複数 ID | 同一 payload 内の複数 ID は observed fields に列挙。primary は手管理ポリシーで後決め |
| `SecurityId` | archive 時に自動生成・自動解決しない。後続の明示 mapping ノートでのみ |

---

## 7. observed fields / validity period

### 7.1 observed fields

ドメインごとに「その日に実際に見えた」フィールド名を列挙する。例:

- Master: `ticker`, `primary_exchange`, `currency_name`, `active`, `share_class_figi`, `composite_figi`
- Price: `open`, `high`, `low`, `close`, `volume`（raw）。`adj*` は別観測または別フラグ
- CA: `split_factor`, `execution_date` / `ex_date`（provider 名のまま）
- Dividend: `ex_date`, `payment_date`, `amount`（provider 名のまま）

**無いフィールドを補完して observed にしない。**

### 7.2 validity period

| ケース | 記録 |
| --- | --- |
| provider が validFrom/To 相当を返す | observed として保存。半開区間変換はアダプタ規則を notes/provenance に残す |
| listing/delisting date のみ | それらを observed。`knownAt` 化禁止 |
| 期間情報なし | `validityPeriod=null` |

---

## 8. raw payload hash / immutable 保存

### 8.1 `rawPayloadHash`（固定）

- アルゴリズム: **SHA-256**  
- 対象: provider から完全受信した **元 response body bytes**（HTTP body そのまま。pretty-print 再シリアライズ禁止）  
- 記録: hex lower-case  
- **圧縮前/後のどちらを hash するか選ぶ運用は禁止。** `rawPayloadHash` は常に元 body  

### 8.2 `storageObjectHash`（任意・別概念）

- ディスク/オブジェクトストレージ上の保存 bytes（gzip 等）に対する hash  
- 保存完全性・転送検証用  
- identity / duplicate / revision 判定の主キーにしない（主は `rawPayloadHash`）  
- 未使用なら null  

### 8.3 immutable raw 方針

| 方針 | 内容 |
| --- | --- |
| Write-once | 同一 `rawPayloadUri` を上書きしない |
| 内容変更 | 新 `archiveId` + 新 URI。旧は残す |
| 圧縮保存 | 可。ただし **元 body の `rawPayloadHash` は不変**。圧縮後は `storageObjectHash` のみ |
| 改ざん検知 | 読み出し時に `rawPayloadHash`（必要なら `storageObjectHash`）を再計算。不一致は当該観測を eligibility から除外 |
| メタデータ | sidecar（JSON lines / 小さな manifest）に §4 フィールド。メタも追記のみ（旧行更新禁止） |

**DB 過剰設計禁止:** 初期は「ディレクトリ + manifest（append-only）」で足りる。RDB/スキーマ追加は本設計の必須条件ではない。

---

## 9. revision candidate detection

| 検知 | 定義 | 動作 |
| --- | --- | --- |
| **同一論理キー・同一 `rawPayloadHash`** | duplicate（§10） | 新観測を `duplicateOf` でリンク。raw 再保存は任意（容量次第でスキップ可） |
| **同一論理キー・異なる `rawPayloadHash`** | **revision 候補** | 新 `archiveId` を追加。`revisionCandidateOf=旧archiveId` および/または `relatedPriorObservationIds` に追加。旧は消さない |
| **論理キー** | `domain + source + requestKey + externalIdentifier(optional)` | ticker 文字列だけでキーにしない |

**禁止:**

- 旧 raw / 旧 manifest 行の上書き（latest-wins 禁止）  
- `revisionCandidateOf` を authoritative replacement とみなすこと  
- 自動置換・自動採用  

訂正の意味解釈（どちらが正しいか）は本設計では自動決定しない。forward では **両方保持**し、eligibility は各版の `eligibilityBoundaryAt` 以降。

---

## 10. duplicate detection

1. `rawPayloadHash` 一致 → byte-identical duplicate（元 body 一致）  
2. 一致時: `observationStatus` は `OBSERVED` のままか `DUPLICATE` 注記。`duplicateOf` を設定  
3. eligibility: 最初の成功観測の `eligibilityBoundaryAt` を代表としてよい（後続 duplicate は境界を早めない）  
4. `storageObjectHash` 一致だけでは duplicate としない（圧縮表現の差があり得る）

---

## 11. missing observation / provider failure

### 11.1 missing observation

予定ジョブが「観測すべきだった」が成功 raw を得られなかった場合:

| フィールド | 値 |
| --- | --- |
| `observationStatus` | `MISSING` |
| `attemptedAt` / `attemptFinishedAt` | 試行開始／終了（required） |
| `fetchedAt` | **null** |
| `ingestedAt` | 欠損レコード確定時刻（required） |
| `rawPayloadHash` | **null** |
| `storageObjectHash` | **null** |
| `rawPayloadUri` | **null** |
| `eligibilityBoundaryAt` | **null**（決定根拠にしない） |

欠損を「前日値の継続」で埋めない。

### 11.2 provider failure

| 例 | `observationStatus` | `fetchedAt` |
| --- | --- | --- |
| 5xx / timeout / DNS / 接続失敗（body 未完全受信） | `PROVIDER_FAILURE` | **null** |
| 401/403 entitlement | `PROVIDER_FAILURE` | **null**（body 未受信の典型） |
| 429 rate limit | `PROVIDER_FAILURE` | **null**（リトライ方針は運用） |
| body 完全受信したがスキーマ崩壊 | `REJECTED_VALIDATION` | **required**（raw は保存、`rawPayloadHash` required） |

failure レコードも append-only で残す。成功に見せかけて補完しない。試行時刻は `attemptedAt` / `attemptFinishedAt` に残す。

---

## 12. coverage（domain × source）

| 項目 | 定義 |
| --- | --- |
| **`coverageStartAt(domain, source)`** | 当該 **domain × source** で **最初に `OBSERVED` の `ingestedAt` が確定した時刻**（UTC）。文書と manifest に明示 |
| **`coverageThroughAt(domain, source)`** | 当該 **domain × source** の **最新 `OBSERVED` の `ingestedAt`**（UTC）。`MISSING` 日があっても through を連続カバレッジと偽らない |
| **global operational start** | 運用ジョブ開始など全体の運用メタ。**coverage と同義にしない** |
| coverage 外 | 当該 domain×source について `decisionAt < coverageStartAt(domain, source)` の区間。forward eligibility なし |

**明示:** coverage は常に `(domain, source)` 単位。グローバル単一の coverage 時刻は定義しない。

**遡及禁止:**

- `coverageStartAt(domain, source)` より前に一括ダウンロードした historical bulk を、開始前 `decisionAt` の as-known として使わない  
- bulk を保存すること自体は「Retrievable 保管」としては可だが、**`eligibilityBoundaryAt` を過去へ戻さない**  
- ラベル例: `STORAGE_ONLY_NOT_FORWARD_ELIGIBLE`（開始前 bulk）  
- 旧称 `archiveStartAt` との同義表現は用いない（削除）

---

## 13. retention

| 対象 | 初期方針 |
| --- | --- |
| immutable raw | **無期限保持を既定**（容量逼迫時のみ方針改訂。改訂は前方適用） |
| failure / missing ログ | 無期限（監査に必要） |
| duplicate raw 実体 | hash 参照で省略可。manifest は残す |
| 削除 | 原則禁止。法的/契約上必須のときのみ tombstone（中身削除理由を残す） |

ライセンス上の保管制約は各 provider ToS に従う。再配布しない（personal/internal 前提を維持）。

---

## 14. forward decision eligibility boundary

### 14.1 定義

ある観測 `O` を forward research の入力候補にする条件:

```text
decisionAt >= O.eligibilityBoundaryAt
かつ O.observationStatus == OBSERVED
かつ hash 検証 OK
かつ domain 固有の未解決 blocker が当該用途で許容
```

### 14.2 `eligibilityBoundaryAt` の決め方（保守）

```text
eligibilityBoundaryAt = max(
  ingestedAt,                          // 検証済み possession
  providerDocumentedCutoffAt?,         // 例: EOD SLA 終了時刻（公式根拠がある場合のみ）
  manualHoldUntil?                     // 運用ホールド
)
```

| 入力 | 使い方 |
| --- | --- |
| `ingestedAt` | **必須の下限** |
| provider EOD cutoff | 公式一次資料がある場合のみ加算。推測禁止 |
| `fetchedAt` | 下限に使ってよいが、通常は `ingestedAt` の方が遅い／安全 |
| `validFrom` / session date | **eligibility に使わない** |
| API `asOf` | **eligibility に使わない** |

### 14.3 明示的にできないこと

- `eligibilityBoundaryAt` を provider historical `knownAt` と呼ぶ  
- `decisionAt >= eligibilityBoundaryAt` を retrospective PIT-safe と呼ぶ  
- Real Backtest GO の根拠にする  

### 14.4 用途ラベル

| ラベル | 意味 |
| --- | --- |
| `FORWARD_ELIGIBLE_CANDIDATE` | §14.1 を満たす |
| `POSSESSION_ONLY` | 保存済みだが eligibility 未達 / failure / 開始前 bulk |
| `RETROSPECTIVE_INELIGIBLE` | 常に（self-archive だけでは）retrospective 不可 |

---

## 15. 最小運用フロー（実装しない擬似手順）

```text
1. スケジュール（例: 米株 EOD 公式 cutoff 後）
2. 許可された最小 source 集合へリクエスト
3. fetchedAt 記録
4. raw を write-once 保存
5. hash・最小検証 → ingestedAt
6. manifest append（§4）
7. duplicate / revision リンク
8. eligibilityBoundaryAt 計算
9. 欠測・failure も append
```

人手の `SecurityId` 紐付け、currency evidence join、CA/Dividend の Grade 判定は **別ゲート**。

---

## 16. 既存契約との対応

| 契約要求 | 本設計 |
| --- | --- |
| `knownAt` / `ingestedAt` / validity 分離 | 分離。knownAt 自動生成なし |
| fetchedAt ≠ historical knownAt | §2 / §14 |
| latest-wins 禁止 | revision は追加のみ |
| ticker → SecurityId 禁止 | externalIdentifier に ticker 単独禁止 |
| currency 推測禁止 | observed のみ。欠落は欠落 |
| Forward ≠ Retrospective | coverage / eligibility / ラベルで分離 |
| Real Backtest NO-GO | §0 維持 |

---

## 17. Out of scope（再掲）

- Provider HTTP client 実装
- OpenFIGI / EODHD / Tiingo / Massive の新規大量接続
- Exchange モデル / DB スキーマ / mapper
- Backtest / Strategy / Android
- retrospective master の PASS 宣言
- Data Contract の緩和

---

## 18. 開始チェックリスト（設計完了条件）

Forward Research 設計ゲートを CONDITIONAL GO とする条件（本ドキュメントで充足）:

- [x] `fetchedAt` / `ingestedAt` 定義
- [x] raw hash + immutable 方針
- [x] source / external identifier 規則
- [x] validity / observed fields
- [x] revision / duplicate
- [x] missing / provider failure
- [x] clock / timezone（UTC）
- [x] retention
- [x] archive 開始日 / coverage
- [x] forward eligibility boundary
- [x] retrospective 遡及禁止の明記
- [x] Real Backtest NO-GO 維持

**まだ足りない（実装・運用ゲート）:**

- [ ] `coverageStartAt(domain, source)` / `coverageThroughAt(domain, source)` の実測確定（各 domain×source の最初／最新 OBSERVED ingest）
- [ ] 圧縮保存を使う場合の `storageObjectHash` 運用有無の確定（`rawPayloadHash` は常に元 body・変更不可）
- [ ] 最小 source 許可リストの運用承認（少数）
- [ ] 手管理 SecurityId mapping 手順
- [ ] provider ToS 上の保管可否の再確認

---

## 19. 次の最小作業（実装しない）

1. 本設計を PR で固定する。  
2. 運用開始時に各 `coverageStartAt(domain, source)`（および以降の `coverageThroughAt(domain, source)`）を文書追記する（成功 OBSERVED ingest 後）。global operational start は別記。  
3. client 実装は別タスク。始めるなら **1 source × 1 domain** の raw 保存のみ。  
4. Real Backtest は既存 Critical blocker 解消まで **NO-GO**。  

---

## 20. Build / test / code

| 項目 | 結果 |
| --- | --- |
| build/test | **文書のみのため未実行** |
| code 変更 | **なし** |
