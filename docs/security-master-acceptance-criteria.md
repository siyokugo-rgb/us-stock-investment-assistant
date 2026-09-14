# Security Master Acceptance Criteria

**調査日 (UTC):** 2026-09-14  
**Repository:** `siyokugo-rgb/us-stock-investment-assistant`  
**Source of Truth:** GitHub `main`  
**Baseline SHA:** `d269a625bfcb4d28642d907765b10f01b561be2a`（照合済・一致）  
**Scope:** 文書契約のみ。Provider / OpenFIGI / EODHD client、DB、mapper、Backtest、Strategy、Android、Broker/楽天連携は対象外。  
**契約購入・有料申込:** なし。  
**Real Backtest:** **NO-GO**（本文書は解消しない）。

本文書は「Security Master として採用可能な source が最低限満たすべき条件」を固定する。  
実装採用・特定 vendor の PASS 証明ではない。

### 維持する不変条件（既存契約・コード）

| 規則 | Source of Truth |
| --- | --- |
| ticker → `SecurityId` 禁止 | `SecurityId` KDoc / [`data-contract.md`](data-contract.md) §1 / PoCs |
| CIK → `SecurityId` 禁止 | `IdentifierType` に CIK 無し；`IssuerIdentifierType.CIK` のみ |
| Issuer ≠ Security | `IssuerId` / `SecurityId` 分離 |
| Issuer:Security = 1:N 許可 | `IssuerSecurityRelation` / `IssuerSecurityRelationIndex` |
| current identifier の過去逆適用禁止 | `SecurityIdentifier` validity + PIT |
| ambiguity 自動解決禁止 | Index は候補全返し；呼び出し側 Fail-Closed |
| latest-wins 禁止 | Index / CA / fundamentals 契約 |
| `knownAt` / `ingestedAt` / validity 期間を混同しない | `SecurityIdentifier`, `IssuerSecurityRelation`, `DailyPrice` |
| currency 推測禁止 | [`data-contract.md`](data-contract.md) §2.1.1 |
| adjusted price → 約定禁止 | `DailyPrice` / price 契約 |
| Corporate Action を Security Master に混在させない | 本文書 §3 / [`corporate-action-split-poc.md`](corporate-action-split-poc.md) |

### 現行コード監査メモ（モデル追加なし）

| 現行型 | 状態 |
| --- | --- |
| `SecurityId` | 内部 identity。ticker から導出禁止 |
| `SecurityIdentifier` | `type`, `value`, `validFrom` inclusive / `validTo` exclusive, `knownAt`, `ingestedAt`, `source` |
| `SecurityIdentifierIndex` | 候補全返し；最新暗黙選択なし |
| `IdentifierType` | `TICKER`, `VENDOR_PERMANENT_ID` のみ（CIK 無し） |
| `IssuerId` / `IssuerSecurityRelation` / `IssuerSecurityRelationIndex` | Issuer↔Security 明示 relation + PIT |
| `DailyPrice` | `currency` + `knownAt` 必須 |
| Exchange / MIC / listing state / share class / trading currency | **正式モデル未実装**（Acceptance で evidence 要件のみ定義） |

**コード矛盾:** 本レビュー中に Critical な仕様矛盾は新規発見せず。修正なし。

関連文書: [`data-contract.md`](data-contract.md)、[`price-source-entitlement-review.md`](price-source-entitlement-review.md)、[`feasibility-gate-review.md`](feasibility-gate-review.md)、[`corporate-action-split-poc.md`](corporate-action-split-poc.md)。

---

## 1. Baseline

| 項目 | 値 |
| --- | --- |
| main HEAD | `d269a625bfcb4d28642d907765b10f01b561be2a` |
| 本ブランチ起点 | 上記 main（一致確認後に作成） |
| コード変更 | なし（文書のみ） |

---

## 2. Scope

### In scope

- Security Master source の Acceptance Criteria（MUST / SHOULD / OPTIONAL / REJECT if absent）
- identity / identifier history / ticker history / venue / currency / listing / share class / issuer relation / PIT / revision / join / ambiguity / completeness
- Retrospective Backtest / Forward Research / Live Trading の用途差
- blocker 一覧と次作業（実装しない）

### Out of scope（今回禁止）

- Provider / OpenFIGI / EODHD client 実装
- model / enum 追加、Exchange・Currency モデル実装
- mapper / DB / Backtest / Strategy / Android
- Broker / 楽天証券マッピング調査・実装
- Corporate Action エンジン本体（接続に必要な identity 要件のみ扱う）

---

## 3. Security Master 責務

Security Master は **「何の Security か」を期間付き・根拠付きで証明する層**である。  
Price / Dividend / CA / Universe の数値そのものは持たない（権威源にしない）。

| ID | 責務 | 含む | 含まない |
| --- | --- | --- | --- |
| **A** | Security identity | 内部 `SecurityId` への安全な外部紐付け | external id の内部 ID 化 |
| **B** | External identifier history | namespace + source + validity + provenance | ticker 文字列だけの恒久化 |
| **C** | Ticker history | 期間付き ticker 版 | current ticker の過去逆適用 |
| **D** | Listing venue / exchange | MIC / exchange evidence（将来モデル化可能な形） | ticker から venue 推測 |
| **E** | Trading currency | period-valid trading currency evidence | US/NASDAQ/timezone → USD 推測 |
| **F** | Listing / delisting state | active/inactive、listing/delisting dates（evidence） | active-only snapshot を完全 master 扱い |
| **G** | Share class | class 区別を潰さない | GOOG/GOOGL の自動統合 |
| **H** | Issuer 関係 | 明示 `IssuerSecurityRelation` 相当 evidence | Issuer → 単一 Security 自動選択 |
| **I** | CA 接続用 identity | CA が同じ Security を指せる外部/内部 ID | split/dividend/merger **イベント本体** |
| **J** | Provenance / version / PIT | `source`, `knownAt`, `ingestedAt`, revision | fetchedAt を historical knownAt 化 |

**分離原則:** Corporate Action（比率・effective・announced・数量調整）は CA 層。Security Master は「どの Security の出来事か」を支える identity のみ。

---

## 4. MUST / SHOULD / OPTIONAL / REJECT if absent

### 4.1 ラベル定義

| ラベル | 意味 |
| --- | --- |
| **MUST** | 欠けると当該用途で Security Master **FAIL**、または mapping **Fail-Closed** |
| **SHOULD** | 欠けると **PARTIAL**；限定用途のみ条件付き可 |
| **OPTIONAL** | あると良い。欠落だけで即 FAIL にはしない |
| **REJECT if absent** | 欠ける／推測が必要な時点で Security Master 候補から除外 |

### 4.2 項目分類

| 項目 | 分類 | 備考 |
| --- | --- | --- |
| permanent external identifier（または同等の安定 ID + namespace） | **MUST**（採用 source 単位） | 無い場合は ticker-only → **REJECT** |
| identifier namespace | **MUST** | FIGI / vendor / ISIN 等を混在させない |
| provider / source | **MUST** | provenance 必須 |
| ticker | **MUST**（上場株式用途） | ticker alone で identity にしない |
| ticker `validFrom` | **MUST** | inclusive；現行 `SecurityIdentifier` と整合 |
| ticker `validTo` | **MUST or explicit unknown** | exclusive；`null` = 未知（最新暗示禁止） |
| exchange / MIC | dual-list では **MUST**；単一会場でも **SHOULD** | ticker から推測は **REJECT** |
| trading currency | **MUST** | `DailyPrice.currency` join に必要 |
| currency validity period | 変更があり得るなら **MUST** | 「常に USD」は根拠無しなら推測扱い |
| listingDate | broad retrospective では実質 **MUST** / 一般に **SHOULD** | survivorship |
| delistingDate / inactive 遷移 | broad retrospective **MUST** | active-only は Backtest Master **FAIL** |
| active/inactive status | **MUST** | as-of 根拠が必要 |
| share class distinction | 複数 class がある場合 **MUST** | 明示無し統合は **REJECT** |
| issuer external identity | **SHOULD**（fundamentals/CA join） | CIK は Issuer 側のみ |
| `knownAt`（master 事実の知識時刻） | retrospective **MUST**；forward self-archive は条件付き | `validFrom` から生成禁止 |
| `ingestedAt` / fetchedAt | possession PIT として **MUST** | historical knownAt 代理禁止 |
| revision / version evidence | **SHOULD**；無ければ revision risk 残存 | overwrite-only は retrospective **INELIGIBLE** |

---

## 5. Identifier rules（Permanent Identifier）

### 5.1 内部 vs 外部

| ID | 性質 |
| --- | --- |
| `SecurityId` | **内部のみ**。アプリが発行・保持。外部 FIGI / vendor ID / ISIN / CUSIP と **同一視禁止** |
| External identifier | `SecurityIdentifier`（または将来拡張）に namespace + value + validity + knownAt + source で保持 |

**禁止:** `external id == SecurityId`  
**禁止:** 「FIGI がある」＝すべて解決済み

### 5.2 External identifier 必須属性

| 属性 | 要件 |
| --- | --- |
| namespace | 必須（例: `FIGI`, `VENDOR:EODHD`, `VENDOR:TIINGO_PERMA`, `ISIN`） |
| provider/source | 必須 |
| validity period | permanent 主張でも reassignment / share-class / listing 単位を文書化。再利用があり得るなら期間必須 |
| 粒度 | **share class 単位**か **listing 単位**か **issuer 単位**かを明示。issuer 単位 ID を Security に直結しない |
| OpenFIGI 等 | mapping 補助。proprietary ID 非返却・filter 用 currency は trading currency evidence にしない（[`price-source-entitlement-review.md`](price-source-entitlement-review.md)） |

### 5.3 現行 `IdentifierType` との関係

現行 enum は `TICKER` / `VENDOR_PERMANENT_ID` のみ。  
本 Acceptance は将来の namespace 拡張を禁止しないが、**今回 enum 追加はしない**。  
vendor permanent id 利用時も ticker→`SecurityId` 禁止・複数候補 Fail-Closed を維持。

---

## 6. Ticker history

現行 `SecurityIdentifier` 契約を Security Master source も満たすこと:

- `validFrom` inclusive / `validTo` exclusive
- `knownAt` / `ingestedAt` / `source`
- `validTo == null` は終了未知（最新の暗黙選択ではない）

| 要件 | 内容 |
| --- | --- |
| 履歴取得 | **current ticker だけから過去期間を捏造しない** |
| ticker change | 同一 `SecurityId` で旧行を閉じ新行を開始できる evidence |
| ticker recycle | 同一文字列が別 Security に再割当 → **別 SecurityId**；文字列 join 禁止 |
| same ticker / different Security | 候補複数保持 |
| same Security / multiple ticker periods | 複数版を保持；上書き禁止 |
| overlapping evidence | 自動マージ禁止；ambiguity |
| unknown `validTo` | 明示的 unknown；open-ended を「現在も有効」と決めつけない |

**禁止:** 現在 ticker の過去逆適用。

---

## 7. Exchange / MIC

現状 Exchange は正式モデル未実装（[`data-contract.md`](data-contract.md) §1）。**今回もモデル追加しない。**

| 項目 | 評価 |
| --- | --- |
| exchange 名 / MIC | 将来属性として表現できる明示 evidence が **SHOULD**（dual listing では **MUST**） |
| primary listing | SHOULD；複数 listing がある場合は primary 主張に根拠必須 |
| venue change | 同一 Security 継続を壊さず記録できること（retrospective で SHOULD） |
| multiple listing | listing 単位 identifier / currency を混同しない |

**禁止:** Ticker 文字列だけで venue 決定。  
**禁止:** timezone / 接尾辞だけで MIC 確定。

単一会場・単一 currency の狭義 research は PARTIAL とし得る。広範 Universe / dual-list では FAIL。

---

## 8. Currency

`DailyPrice.currency` は必須。Security Master は少なくとも次を根拠付きで解決できること:

> その Security / listing / period の trading currency

| 意味 | 要件 |
| --- | --- |
| currency code | 明示コード（source が返す明示値） |
| validity period | 変更があり得るなら期間必須 |
| venue 関係 | listing ごとに currency が異なり得る |
| currency 変更 | 履歴、または「変更無し」の一次根拠 |
| ADR / dual listing / multiple currency | 同一 ticker では解決不能；class + venue + currency evidence |

**禁止:** US 株だから USD  
**禁止:** NASDAQ だから USD  
**禁止:** timezone から currency 推測  

| currency evidence の PIT | 帰結 |
| --- | --- |
| `knownAt`（または同等 knowledge time）あり | join 候補 |
| PIT 無し（current snapshot のみ） | **DailyPrice mapping blocker 残**；retrospective master は currency 項 FAIL/PARTIAL |

currency 未解決と price `knownAt` 未解決は **独立 blocker**（data-contract §2.1.1）。

---

## 9. Listing / Delisting

| 要件 | 分類 |
| --- | --- |
| listingDate（または同等） | SHOULD / broad retrospective では実質 MUST |
| delistingDate（または inactive 遷移） | broad retrospective **MUST** |
| inactive Security 保持 | **MUST**（ID を消さない） |
| historical identifier 保持 | **MUST** |
| acquisition 後も履歴参照可能 | **MUST**（終了と後継関係は CA/relation 層と連携） |

**「現在 active な Security 一覧」だけでは Backtest 用途 PASS にしない。**  
active-only → Retrospective Backtest Master = **FAIL**（Survivorship Bias blocker）。

---

## 10. Share Class

| 要件 | 扱い |
| --- | --- |
| share class distinction | 複数 class がある場合 MUST |
| class identifier / 明示ラベル | SHOULD；無ければ class unknown として統合禁止 |
| issuer relation | 明示 1:N |
| multiple securities per issuer | 許可；単一自動選択禁止 |

| provider が share class を明示しない場合 | Fail-Closed |
| --- | --- |
| 1 issuer に ticker 複数 | 複数 `SecurityId` 候補のまま；代表銘柄を勝手に選ばない |
| class 不明のまま 1 Security に統合 | **REJECT** |
| 「同じ会社だから同一 Security」 | **REJECT** |

---

## 11. Issuer relation

| 規則 | 内容 |
| --- | --- |
| 結合 | `IssuerSecurityRelation` 相当の明示 evidence のみ |
| CIK | Issuer 側のみ。`SecurityIdentifier` に載せない（現行維持） |
| 自動 1 件選択 | **禁止** |
| 期間 + knownAt | validity と knowledge time を分離 |

Issuer 関係が無い状態で fundamentals を Security に直結しない。

---

## 12. PIT / knownAt

| 概念 | 意味 | 例 |
| --- | --- | --- |
| validity period | 現実世界でその事実が成り立つ期間 | ticker change **effective** 2020-01-01 |
| `knownAt` | その版を根拠付きで決定に使えた時刻 | 2019-12-20 announcement、または 2020-01-01 official publication |
| `ingestedAt` / fetchedAt | 本システムが保持した時刻 | 取得成功直後 |

**禁止:** `validFrom = knownAt` の自動等式  
**禁止:** `listingDate = knownAt`  
**禁止:** `fetchedAt = historical knownAt`

| PIT source | retrospective backtest | forward self-archive |
| --- | --- | --- |
| 無し（current master snapshot のみ） | **INELIGIBLE** | **条件付き候補**（当日以降を自己アーカイブし、アーカイブ時刻を境界として明示する場合のみ） |
| あり（行/版単位） | 候補 | 候補 |
| SLA のみで版履歴なし | as-known 内容は INELIGIBLE；availability lower-bound は別議論 | forward 制約に使える場合あり |

---

## 13. Revision / Correction

| 訂正例 | 要求 |
| --- | --- |
| ticker date / delisting date / currency / exchange / identifier correction | 新版を追加；旧版を黙って上書きしない |

**禁止:** current response だけを historical truth として上書き。

| version / updated timestamp / archive | 帰結 |
| --- | --- |
| あり | revision を版管理できる → SHOULD 充足 |
| 無し | **revision risk 残存**；Retrospective Backtest Master は PASS 不可（最大 PARTIAL/FAIL） |

---

## 14. Join contract

### 14.1 許可経路（概念）

```text
Provider Price row
  → provider external identifier (namespace + value + as-of)
  → Security Master evidence (validity ∩ knownAt <= decisionAt)
  → 候補 SecurityId 集合
  → 候補数 == 1 のときのみ SecurityId 確定（それ以外 Fail-Closed）
  → period-valid currency evidence（同一 identity 経路）
  → DailyPrice candidate（price knownAt / raw OHLC 等は別 blocker）
```

Universe / Dividend / CA も同じ identity 経路を共有する。各ドメイン数値の PIT は各層の blocker。

### 14.2 禁止経路

```text
price.symbol → ticker 文字列だけ → SecurityId
```

```text
IssuerId → （自動で）単一 SecurityId
```

```text
CIK / CompanyFacts → SecurityId
```

```text
FIGI が1件 → validity / class / venue / currency を無視して解決済み
```

---

## 15. Ambiguity（Fail-Closed）

以下は Fail-Closed（候補集合保持・自動選択しない）:

- external id 複数候補
- ticker period overlap（矛盾 evidence）
- exchange 不明で venue 依存の一意性が必要な場合
- currency 不明
- share class 不明で複数 class があり得る場合
- reused ticker
- issuer relation 複数（単一 Security が必要な処理）
- validity 期間不明で一意性を証明不能

**候補集合を保持すること**と**自動選択しないこと**を必須とする。  
`SecurityIdentifierIndex` / `IssuerSecurityRelationIndex` の現行挙動と整合。

---

## 16. Completeness

「1 件取れた」≠ feed 全体の証明。

| 項目 | 意図 |
| --- | --- |
| active securities | 現役カバレッジ |
| delisted securities | survivorship |
| historical identifier changes | ticker/permanent id 履歴 |
| venue changes | listing 移転 |
| currency changes | trading currency 履歴 |
| share classes | class 分離 |
| coverage start / through | 期間宣言 |
| missing rows | 欠測の明示（unknown ≠ empty） |

**provider の “complete” 文言だけで完全性を自動信用しない。**  
宣言と実測（サンプル監査）が無い broad Backtest Master は PASS にしない。

---

## 17. Acceptance matrix（PASS / PARTIAL / FAIL）

| 領域 | PASS | PARTIAL | FAIL |
| --- | --- | --- | --- |
| Identity | namespace 付き安定外部 ID ↔ 内部 SecurityId の期間付き evidence | 手運用で極少数のみ紐付け | ticker/CIK のみ、または external=internal |
| Ticker history | change/recycle を捏造せず表現 | 短い期間のみ | current snapshot のみ／過去逆適用 |
| Venue | MIC/exchange evidence（必要範囲） | 単一会場仮定を明示した研究 | ticker から venue 推測 |
| Currency | period-valid explicit currency + PIT | forward archive のみ | USD 推測が必要 |
| Delisted | inactive 保持 + 履歴参照 | 一部 delisted のみ | active-only を完全 master 主張 |
| Share class | 複数 Security を潰さない | class 不明を未解決のまま保持 | 自動統合 |
| Issuer relation | 明示 1:N + PIT | relation 部分的 | Issuer から単一自動選択 |
| PIT / provenance | knownAt ≠ validFrom；ingestedAt 分離 | forward self-archive のみ | fetchedAt→knownAt |
| Revision | 版管理または archive | risk 明示の PARTIAL | current overwrite を historical truth 扱い |

### 総合ゲート（source 単位）

| 結果 | 条件 |
| --- | --- |
| **PASS** | 対象用途の MUST がすべて充足し、REJECT 条件に触れない |
| **PARTIAL** | forward または狭義用途のみ；retrospective PIT / delisted / revision の欠落を明示 |
| **FAIL** | ticker current snapshot のみ、currency 推測必要、delisted 欠如、identifier collision 解決不能、など |

**現状（2026-09-14）:** 特定 vendor を PASS 認定しない。OpenFIGI 単独・EODHD list 単独・Alpha Vantage 単独は、フル Retrospective Master としては **FAIL または PARTIAL 止まり**（[`price-source-entitlement-review.md`](price-source-entitlement-review.md) と整合）。

---

## 18. 用途差（Retrospective / Forward / Live）

同一 source が 3 用途すべてを満たす必要はない。  
**forward で使える ≠ 過去 Backtest に使える。**

### A. Retrospective Backtest Master

| MUST 寄り | 内容 |
| --- | --- |
| historical identifier + ticker periods | 捏造なし |
| delisted / inactive 保持 | survivorship |
| currency period evidence + knowledge time | DailyPrice join |
| master 事実の historical knownAt または版付き archive | as-known |
| revision 耐性 | ledger or immutable archive |
| share class / issuer 1:N | 潰さない |

**PIT 無し current snapshot → INELIGIBLE**

### B. Forward Research Master

| 条件付き可 | 内容 |
| --- | --- |
| 公式更新後に self-archive | archive 時刻を境界として明示 |
| explicit currency / venue on archive day | 推測なし |
| 手管理 SecurityId 紐付け | ticker 自動生成なし |
| CA は別層 | master にイベント混在させない |

Retrospective へ昇格させない。Real Backtest の根拠にしない。

### C. Live Trading Master

| 追加観点（今回非実装） | 内容 |
| --- | --- |
| 低遅延の listing 状態 | 売買可否の別確認が必要になり得る |
| broker-side mapping | §18.1 |
| 当日訂正 | revision 取り込み方針 |

Acceptance Criteria 自体は **楽天証券に依存しない。**

#### 18.1 楽天証券・少額運用（記載のみ）

将来 live では Security Master とは別に broker-side mapping が必要になり得る:

- 楽天で取扱対象か
- 発注可能 symbol
- currency
- 端株可否
- listing 状態

**今回は調査・実装しない。** 手数料・ポイント等を本契約に混入しない。

---

## 19. Blocker 一覧

### Critical

1. Retrospective 向け historical master `knownAt` / 版管理の欠如（多くの retail master）
2. period-valid trading currency evidence（推測禁止）の未確立
3. ticker-only / ambiguous external id → `SecurityId` 安全 join の未確立
4. delisted/inactive 履歴不足による Survivorship Bias（broad universe）
5. share class 不明時の統合圧力（Fail-Closed 維持が必要）

### High

1. Exchange/MIC モデル未実装＋evidence 不足（dual listing）
2. revision/correction ledger 不在
3. issuer relation の運用データ不足（モデル境界は RESOLVED）
4. CA 層との identity 接続（CA 自体の PIT は別 Critical）
5. completeness 宣言と実測の欠如
6. 有料 master の費用制約（経済は技術 FAIL にしないが Live/運用制約）

### 既存ドメインとの関係

| ドメイン | 関係 |
| --- | --- |
| Price knownAt | 独立 Critical（price entitlement レビュー） |
| Currency | Master または reference join；未解決なら DailyPrice 不可 |
| CA | イベントは CA 層；identity は本 Master |
| Universe | membership は別；master 不完全なら広範 universe FAIL |

---

## 20. Next step（実装しない）

1. 本 Acceptance を Draft PR で固定する。  
2. 候補 source（OpenFIGI / EODHD reference / Tiingo meta / institutional）を本表に照らして PASS/PARTIAL/FAIL 採点する（client 実装なし・申込なし）。  
3. Forward-only self-archive の運用境界を設計ノート化する場合も、Retrospective へ黙って流用しない。  
4. Exchange/MIC・listing state・share class のモデル化は Acceptance PASS 見込みが付いてから（今回はやらない）。  
5. Real Backtest は Master だけでなく Price knownAt / CA PIT も揃うまで **NO-GO**。

---

## 21. 最終判定

| 項目 | 判定 |
| --- | --- |
| Acceptance Criteria 文書 | **成立（本 PR）** |
| いずれかの source を Retrospective Master PASS | **未認定** |
| Forward Research Master | 条件付き候補は議論可（実装・契約なし） |
| Live Trading Master | broker mapping 未着手；未認定 |
| Data Contract 弱体化 | **なし** |
| コード変更 | **なし** |
| Real Backtest | **NO-GO** |

---

## 22. Build / test

**文書のみのため未実行。**
