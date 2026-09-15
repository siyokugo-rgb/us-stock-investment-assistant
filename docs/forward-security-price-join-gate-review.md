# Forward Security Master × Price Join Gate Review

**Date (UTC):** 2026-09-15  
**Repository:** `siyokugo-rgb/us-stock-investment-assistant`  
**Review type:** Gate Review only（Kotlin / mapper / DB / Backtest 実装なし）  
**Baseline `origin/main` HEAD:** `1ca9877db4711bae5fa97caae609ff24055f65b8`  
**PR #20:** MERGED（`mergedAt=2026-09-15T05:56:27Z`）  
**PR #20 head (ancestor verified):** `f143116ee0fb825072e34b7c385c0eff47642867`

| Gate | Verdict |
| --- | --- |
| Security ↔ Price Forward Join | **PARTIAL** |
| SecurityId issuance | **NO-GO** |
| DailyPrice mapping | **NO-GO** |
| Forward Research | **CONDITIONAL GO**（維持） |
| Real Backtest | **NO-GO**（維持） |

---

## 0. Baseline / merge confirmation

| Check | Result |
| --- | --- |
| `git fetch` + `checkout main` + `pull --ff-only` | OK |
| local `main` == `origin/main` | OK (`1ca9877db4711bae5fa97caae609ff24055f65b8`) |
| PR #20 state | **MERGED** |
| `f143116…` is ancestor of `main` | OK |

PR #19（OpenFIGI Security Master forward raw archive）および PR #20（Alpha Vantage PRICE forward raw archive）が main に存在する前提で本 Gate を実施する。

---

## 1. Development stage

```text
Forward Self-Archive design
  → Security Master raw archive (OpenFIGI)
  → Price raw archive (Alpha Vantage TIME_SERIES_DAILY)
  → 【本レビュー】Security × Price Join Gate Review
```

**まだ進まないもの:** DailyPrice mapping、production mapper、DB、Repository、Backtest、Strategy、QDR/Dogs/Quality/Dividend Capture、Portfolio、Android UI、scheduler、broker 連携、新 Provider client、SecurityId 自動生成コード。

---

## 2. What exists on main (facts only)

### 2.1 Common archive primitives

`archive.poc`（PR #19 系）:

- `ManifestRecord` / `ManifestStore` / `ImmutableRawStore` / `Sha256Hex` / coverage / status enums
- `eligibilityBoundaryAt` = OBSERVED 時の `ingestedAt`（forward eligibility）
- historical `knownAt` は **生成しない**
- coverage = OBSERVED `ingestedAt` のみ（payload 内日付ではない）

### 2.2 Security Master forward raw archive (PR #19)

| Item | Value |
| --- | --- |
| Domain | `SECURITY_MASTER` |
| Source | `openfigi.v3.mapping` |
| Endpoint | `POST https://api.openfigi.com/v3/mapping` |
| Purpose | OpenFIGI mapping response の exact bytes を possession evidence として保存 |
| SecurityId | **非生成** |
| knownAt | **非生成** |

OBSERVED 時、単一 job / 単一 data candidate / 単一非空 `figi` のときのみ:

- `externalIdentifier` = その FIGI
- `externalIdentifierNamespace` = `figi`

複数候補は `externalIdentifier=null`（first-FIGI 自動選択なし）。

Validator が観測し得る response fields（存在すれば `observedFields`）:

`figi`, `compositeFIGI`, `shareClassFIGI`, `ticker`, `exchCode`, `securityType`, `marketSector`, `name`

### 2.3 Price forward raw archive (PR #20)

| Item | Value |
| --- | --- |
| Domain | `PRICE` |
| Source | `alphavantage.time_series_daily.raw` |
| Endpoint | `GET …/query` `function=TIME_SERIES_DAILY` |
| Purpose | raw daily response bytes の possession evidence |
| Provider symbol | **request provenance only** |
| SecurityId | **非生成** |
| DailyPrice mapping | **未実装** |
| currency | **UNRESOLVED_FROM_TIME_SERIES_DAILY** |
| historical knownAt | **UNRESOLVED / UNUSABLE** |

`requestKey` 例:

```text
GET|/query|function=TIME_SERIES_DAILY|symbol={SYMBOL}|outputsize={compact|full}
```

API key は requestKey / transportFailureMessage / manifest に入らない。

### 2.4 Existing domain types (not join engines)

| Type | Role |
| --- | --- |
| `SecurityId` | 内部 identity（ticker 導出禁止） |
| `IssuerId` | Issuer identity（Security と別） |
| `SecurityIdentifier` | `IdentifierType.TICKER` / `IdentifierType.VENDOR_PERMANENT_ID` + validity + `knownAt` + `ingestedAt` |
| `SecurityIdentifierIndex` | 候補全返し（latest-wins なし） |
| `IssuerSecurityRelation` | Issuer↔Security 期間付き関係 |
| `DailyPrice` | `securityId` + **必須 `currency`** + **必須 `knownAt`** + OHLCV |

Exchange / MIC / share-class / trading-currency の **正式モデルは未実装**（Acceptance Criteria が evidence 要件のみ定義）。

---

## 3. Theme audit (A–M)

### A. Provider symbol identity

Alpha Vantage `symbol` は **request provenance** のみ。

禁止（現行コード・文書とも維持）:

- symbol → SecurityId
- symbol → FIGI
- symbol → IssuerId
- symbol → USD / trading currency

単独 ticker join は **禁止維持**。

### B. OpenFIGI identity evidence

| Field | Evidence meaning | Not meaning |
| --- | --- | --- |
| `figi` | listing/instrument 向け FIGI 候補（OpenFIGI の row-level id） | 内部 SecurityId |
| `compositeFIGI` | composite / aggregated listing identity 候補 | share-class 唯一解の自動証明 |
| `shareClassFIGI` | share-class 粒度 identity 候補 | issuer identity |
| `ticker` | provider/OpenFIGI 上の表示・照合補助 | 安定 external id / SecurityId |
| `exchCode` | OpenFIGI 交換所コード（venue 補助） | 正式 ISO MIC モデル確定 |
| `securityType` / `marketSector` | 分類補助 | trading currency / SecurityId |
| `name` | 表示補助 | identity |

**FIGI 取得成功 ≠ SecurityId 確定**（Acceptance §5 / §14.2 と一致）。

### C. Stable external identifier

本 Gate での候補:

- namespace = `figi`
- value = 一意に確定した `figi`（archive が `externalIdentifier` に載せた場合）

ただし:

- FIGI は **外部 id** であり内部 `SecurityId` ではない
- composite / shareClass の役割分離なしに「1 FIGI = 1 Security」と断定しない
- ticker history / recycle 証拠が無い限り、過去帰属には使えない

### D. SecurityId join

現行 main に **Price archive ↔ Security archive を結ぶ join 実装は無い**。

概念上の許可経路は Acceptance §14.1:

```text
Price row
  → external id (namespace+value+as-of)
  → Security Master evidence (validity ∩ knowledge time)
  → candidate SecurityId set
  → |set|==1 のときのみ確定
  → period-valid trading currency
  → DailyPrice candidate（別 blocker あり）
```

禁止経路（§14.2）: ticker-only、Issuer 自動単一化、CIK→SecurityId、FIGI 1件だけの解決済み扱い。

### E. Trading currency

- PRICE archive: **未解決**
- OpenFIGI: trading currency evidence **として採用しない**（filter 用 currency と混同禁止；既存 Price entitlement / Security feasibility 判定を維持）
- `DailyPrice.currency` は必須 → mapping 開始不可

### F. Venue / MIC

- OpenFIGI `exchCode` は **PARTIAL venue evidence**
- 正式 MIC / listing モデルは未実装
- dual listing / class ambiguity がある用途では ticker+FIGI だけでは不足し得る
- **本 Gate 判定:** DailyPrice / 広範 Universe 向け join では **Critical**。狭義・単一上場・一意 FIGI の identity 候補検討までは **High（保留可）**。MIC コード追加は今回しない。

### G. Share class

- `shareClassFIGI` は class 粒度の補助 evidence
- `IssuerId != SecurityId`（GOOG/GOOGL 等を Issuer 単位で潰さない）
- class 不明かつ複数 class があり得る場合は Fail-Closed（Acceptance §15）

### H. Validity period

- OpenFIGI current mapping response は **ticker validFrom/To 履歴を提供しない**（feasibility: retrospective FAIL / history 不足）
- archive の `eligibilityBoundaryAt` は **possession 以後の forward eligibility** であり identifier validity ではない
- validity ≠ knownAt ≠ eligibilityBoundaryAt

### I. Forward eligibility boundary

両 archive とも:

- OBSERVED ⇒ `eligibilityBoundaryAt = ingestedAt`
- 非 OBSERVED ⇒ null（coverage なし）

Join もこの境界より前へ遡及しない。

### J. Revision / correction

共通 archive 契約:

- same requestKey + same hash → duplicate candidate
- same requestKey + different hash → revision candidate
- latest-wins / 自動 correction 確定 / 旧 raw 削除 / 過去 eligibility 書換 **禁止**

Join 側でも revision conflict は Fail-Closed（自動で「正しい mapping」へ切替しない）。

### K. Ticker reuse / change

- current mapping を過去 Price へ逆適用 **禁止**
- 同一文字列 = 同一 Security **禁止**
- recycle 時は別 SecurityId（Acceptance §6）

### L. Delisted / inactive

現行 OpenFIGI forward archive は listing state の正式モデルを持たない。  
inactive/delisted を理由に identity を自動確定しない。証拠不足は unresolved。

### M. Issuer–security relation

`IssuerSecurityRelation` は存在するが、本 join Gate では:

- Issuer 解決は **別問題**
- Price → Security 帰属に Issuer を経由した単一化は禁止

---

## 4. Minimum join evidence (acceptance, not a production model)

Price observation を特定 `SecurityId` に forward-only で帰属させるために、**最低限**次を要求する。

| Evidence | Role |
| --- | --- |
| `priceArchiveId` | PRICE possession |
| price `requestKey` / provider / provider symbol | provenance（identity ではない） |
| `priceEligibilityBoundaryAt` | Price evidence が usable になる最早時刻 |
| `mappingArchiveId` | SECURITY_MASTER possession |
| mapping `externalIdentifierNamespace` + `externalIdentifier` | 安定外部 id（現状は一意 `figi` のみ自動載荷） |
| share-class evidence | 複数 class があり得る場合の必須分離 |
| venue evidence | dual-list / listing 曖昧性がある場合の必須分離（`exchCode` は補助、MIC モデルは未整備） |
| `mappingEligibilityBoundaryAt` | mapping evidence の forward 境界 |
| ambiguity count == 0（Fail-Closed） | 複数 FIGI / overlap / conflict で自動確定禁止 |
| trading currency evidence（DailyPrice 用） | identity とは別；明示 trading currency のみ |

**概念上の最小 join candidate（実装禁止・文書概念のみ）:**

```text
PriceSecurityJoinCandidate
  priceArchiveId
  mappingArchiveId
  providerSymbol          // provenance only
  externalIdentifierNamespace
  externalIdentifier
  venueEvidence?          // exchCode / future MIC
  shareClassEvidence?
  tradingCurrencyEvidence?
  joinEligibleAt
  status                  // RESOLVED | AMBIGUOUS | MISSING_* | INELIGIBLE_TIME | REVISION_CONFLICT | FORBIDDEN_BACKFILL
```

過剰な production class / DB schema は作らない。

---

## 5. `joinEligibleAt` (forward only)

両 evidence が揃うまで join 可能扱いにしない。

```text
joinEligibleAt =
  max(
    priceEligibilityBoundaryAt,
    mappingEligibilityBoundaryAt
  )
```

制約:

- `joinEligibleAt >= priceEligibilityBoundaryAt`
- `joinEligibleAt >= mappingEligibilityBoundaryAt`
- **`joinEligibleAt` を historical `knownAt` と呼ばない**
- retrospective へ遡及しない
- `validFrom` / tickerStartDate / marketDate / Last Refreshed から knownAt / joinEligibleAt を生成しない

### Time-direction QA

| Scenario | Allowed interpretation |
| --- | --- |
| 09:00 Price OBSERVED、10:00 Mapping OBSERVED | 09:00 時点で identity 既知だった扱い **禁止**。join 可能は **10:00 以降** |
| 09:00 Mapping OBSERVED、16:30 Price OBSERVED | join 可能は **16:30 以降** |
| current mapping を old price へ backfill | **FORBIDDEN** |

---

## 6. Ambiguity / Fail-Closed status (minimal)

自動確定禁止（候補保持は可）:

- symbol only
- 複数 FIGI / multi-candidate
- share class 不明（複数 class リスク）
- venue 不明（listing 一意性に必要な場合）
- ticker reuse 疑い
- overlapping validity / revision conflict
- currency 不明（DailyPrice 経路）
- external identifier ambiguity

文書上の最小 status:

| Status | Meaning |
| --- | --- |
| `RESOLVED` | identity 一意 + 必要粒度充足 + joinEligibleAt 成立（DailyPrice は別） |
| `AMBIGUOUS` | 複数候補 |
| `MISSING_SECURITY_EVIDENCE` | mapping / external id 不足 |
| `MISSING_VENUE` | venue が必要なのに不足 |
| `MISSING_CURRENCY` | DailyPrice に必要な trading currency 不足 |
| `INELIGIBLE_TIME` | joinEligibleAt 未到達 / evidence 片側のみ |
| `REVISION_CONFLICT` | mapping/price revision が自動解決不能 |
| `FORBIDDEN_BACKFILL` | current→past 逆適用など時間方向違反 |

巨大 enum は作らない。

---

## 7. SecurityId issuance gate (document only)

内部 `SecurityId` を **新規発行してよい**最小条件（コード実装なし）:

1. stable external id + namespace が一意（例: `figi` + 単一 FIGI）
2. security / share-class 粒度が明確（必要なら `shareClassFIGI` 等）
3. listing/venue 粒度が用途上必要なら充足（dual-list では MIC/venue MUST）
4. ambiguity count = 0
5. forward provenance あり（mapping archive OBSERVED + `eligibilityBoundaryAt`）
6. Issuer 関係は **別ゲート**（自動で Issuer から単一 Security を選ばない）

**現時点:** OpenFIGI raw archive だけでは ticker history / trading currency / 正式 MIC / revision ledger が不足 → **SecurityId issuance = NO-GO**。

---

## 8. DailyPrice mapping gate (document only)

`DailyPrice` 開始に必要なもの（現行型の必須項目と一致）:

| Requirement | Current state |
| --- | --- |
| resolved `SecurityId` | NO-GO（上節） |
| explicit **trading** currency | UNRESOLVED（AV daily / OpenFIGI とも不足） |
| raw OHLCV validation | PRICE archive validation は possession 用に存在。domain mapping は未着手 |
| forward eligibility | archive 境界は存在 |
| raw vs adjusted 境界 | TIME_SERIES_DAILY only（adjusted 混在禁止維持） |
| historical `knownAt` | UNRESOLVED / UNUSABLE（Backtest 用）。Forward research でも `DailyPrice.knownAt` を捏造しない |

**DailyPrice mapping = NO-GO**。

---

## 9. Acceptance matrix

| Case | Inputs | Verdict |
| --- | --- | --- |
| 1 | symbol only | **FAIL**（identity / DailyPrice とも） |
| 2 | symbol + unique FIGI, venue unknown | Identity **候補のみ（PARTIAL）**。SecurityId issuance **NO-GO**（dual-list/class リスク未遮断）。DailyPrice **NO-GO** |
| 3 | unique FIGI + share class + venue, currency unknown | Security **identity 候補は前進可**。SecurityId issuance は履歴/revision 不足でなお **NO-GO（厳格）** / 将来条件付き。DailyPrice **NO-GO** |
| 4 | identity + venue + **trading currency** + 両 archive OBSERVED | Forward join **候補**（`joinEligibleAt=max(...)`）。なお historical knownAt / CA PIT が無い限り Real Backtest は NO-GO |
| 5 | mapping が Price より後 | `joinEligibleAt` は **後側**。Price 時刻への遡及禁止 |
| 6 | 複数 FIGI | **AMBIGUOUS**（自動確定禁止） |
| 7 | current mapping → old price | **FORBIDDEN** |
| 8 | ticker reuse | **FORBIDDEN / UNRESOLVED** |

---

## 10. OpenFIGI alone sufficiency

| Need | OpenFIGI forward raw archive alone |
| --- | --- |
| stable external id (FIGI) | PARTIAL（一意時のみ manifest 載荷） |
| share class | PARTIAL（`shareClassFIGI` 補助；必須モデルなし） |
| listing / venue / MIC | PARTIAL（`exchCode` only；MIC モデルなし） |
| trading currency | **不足（Critical for DailyPrice）** |
| validity / ticker history | **不足（Critical for retrospective; High for long-lived forward）** |
| revision history | PARTIAL（raw revision candidate のみ；意味的 correction 非確定） |

**結論:** OpenFIGI 単独では SecurityId issuance / DailyPrice / Real Backtest を開かない。Forward Research の raw mapping aid としては CONDITIONAL。

---

## 11. Answers (required)

| # | Question | Answer |
| --- | --- | --- |
| A | 現在の main だけで Security Master × Price forward join は成立するか | **PARTIAL**。両 raw archive は存在するが、安全な帰属（SecurityId 確定 join）層は未整備。ticker join は禁止のまま |
| B | SecurityId 発行開始可能か | **NO-GO** |
| C | DailyPrice mapping 開始可能か | **NO-GO** |
| D | trading currency は解決したか | **未解決** |
| E | venue/MIC 不足は Critical か High か | DailyPrice / dual-list 向け **Critical**。狭義 identity 候補検討では **High（保留可）** |
| F | OpenFIGI 単独で足りるか | **足りない** |
| G | 次の最小作業 | **A. Forward Security Mapping Evidence 実装**（OpenFIGI OBSERVED raw を、SecurityId を発行せずに forward mapping evidence / join candidate 入力へ落とす境界。currency / DailyPrice / Backtest は含めない） |

---

## 12. Remaining risks

### Critical

1. Trading currency evidence 不在 → DailyPrice 不可  
2. Historical knownAt / retrospective Security Master 不在 → Real Backtest NO-GO  
3. CA PIT / survivorship / universe entitlement 等の既存 Critical（本 Gate で解消しない）  
4. ticker-only / current-mapping backfill を許せば identity 破壊（禁止維持が必須）

### High

1. Venue/MIC モデル未整備（dual-list / listing ambiguity）  
2. Share-class モデル未整備  
3. Ticker history / recycle evidence 不足  
4. Mapping revision の意味的解決ルール未定義（candidate のみ）  
5. 1 万円運用では有料 identity/currency provider が経済的に重い可能性（下記）

---

## 13. ¥10,000 economics (separated from technical verdict)

| Lens | Note |
| --- | --- |
| Technical | currency / richer identity に有料 source が必要でも、それは **技術要件** として記録する |
| Economic | 口座 1 万円想定では、追加有料 Provider は **UNSUITABLE / defer** になり得る |
| Rule | 「高いから技術 FAIL」と書かない。技術 FAIL と経済 UNSUITABLE を分離 |

本 Gate の技術判定は経済事情で緩めない。

---

## 14. Real Backtest / Forward Research

| Gate | Verdict | Why |
| --- | --- | --- |
| Forward Research | **CONDITIONAL GO** | raw possession archives（PR #19/#20）は維持。join/SecurityId/DailyPrice は未開放 |
| Real Backtest | **NO-GO** | historical knownAt、retrospective Security Master、CA PIT、currency PIT、survivorship 等は未解消。Forward join 設計が部分的に見えても GO にしない |

---

## 15. Reviewer verdicts

| Role | Verdict | Note |
| --- | --- | --- |
| SE | **PASS (review)** | 段階境界・禁止経路・次最小作業が明確 |
| Programmer | **PASS (no code)** | Kotlin 変更なし。実装誘惑（mapper/DB）に進んでいない |
| Data Integrity | **PASS (constraints held)** | ticker join / backfill / currency 推測 / Issuer 混同を開いていない |
| QA | **PASS (consistency)** | PR #19/#20 境界を弱めず、未解決を PASS に丸めていない |

---

## 16. Next minimal work (choose one)

**推奨: A. Forward Security Mapping Evidence 実装**

範囲（将来 PR）:

- OpenFIGI OBSERVED archive を入力に、SecurityId 非発行の mapping evidence / join-candidate 記録
- `joinEligibleAt = max(priceEligibilityBoundaryAt, mappingEligibilityBoundaryAt)` の文書実装対応
- ambiguity Fail-Closed
- **含めない:** SecurityId 自動発行、DailyPrice、currency 推測、Backtest、Android

代替（本 Gate では次点）:

- B. Trading Currency source PoC  
- C. Venue/MIC evidence model  
- D. SecurityId issuance boundary（文書＋将来実装）  
- E. Price→Security join candidate（A の一部として吸収推奨）

---

## 17. Merge advice for this review PR

- Draft PR としてレビュー可  
- **main へ自動 merge しない**  
- 本文書は実装許可書ではない。SecurityId / DailyPrice / Real Backtest を開かない  

---

## 18. References (existing SoT)

- [`forward-self-archive-design.md`](forward-self-archive-design.md)
- [`openfigi-forward-archive-poc.md`](openfigi-forward-archive-poc.md)
- [`price-forward-archive-poc.md`](price-forward-archive-poc.md)
- [`security-master-acceptance-criteria.md`](security-master-acceptance-criteria.md)
- [`security-master-feasibility-poc.md`](security-master-feasibility-poc.md)
- [`price-source-entitlement-review.md`](price-source-entitlement-review.md)
- [`price-data-poc.md`](price-data-poc.md)
- [`data-contract.md`](data-contract.md)
- [`feasibility-gate-review.md`](feasibility-gate-review.md)
