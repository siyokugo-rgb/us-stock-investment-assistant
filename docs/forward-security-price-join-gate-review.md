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

#### Binding evidence（provider symbol ↔ OpenFIGI external id）

provider symbol 文字列と OpenFIGI response 上の ticker / FIGI が一致するだけでは **join 禁止**。

必要なのは、Price 側 provider symbol と Security Master 側 external id を結ぶ **binding evidence** である。

概念例（SecurityId / DailyPrice は禁止。binding candidate のみ）:

```text
ProviderSymbolBindingEvidence
  priceArchiveId / priceProvider / providerSymbol   // providerSymbol は PRICE requestKey から strict 導出
  mappingArchiveId / mappingRequestKey
  mappingRequestPayloadHash / mappingRequestPayloadUri
  mappingIdType / mappingIdValue / mappingExchCode?
  externalIdentifier? / namespace?                    // manifest 一意時のみコピー
  bindingEligibleAt = max(price, mapping) eligibility // historical knownAt ではない
  status = CANDIDATE | AMBIGUOUS | INELIGIBLE
```

意味:

- AV `symbol` と OpenFIGI `ticker` の **文字列一致 alone = FAIL**
- OpenFIGI mapping の **request input**（何を問い合わせてその FIGI を得たか）が後から検証できる必要がある
- response 側 FIGI だけでは「どの provider symbol に対する mapping か」を安全に固定できない

**現状判定（main / PR #22 以降）:**

- OpenFIGI archive は **response body** に加え **secret-free request body**（`request/{archiveId}.request.raw`）を immutable 保存する
- `requestKey = POST|/v3/mapping|sha256:{requestPayloadHash}` と `requestPayloadHash` / `requestPayloadUri` が整合
- `idType` / `idValue` / `exchCode` は request raw から strict 再検証可能
- `ProviderSymbolBindingEvidence` は PRICE OBSERVED + OpenFIGI OBSERVED + TICKER request idValue==providerSymbol + one-candidate のときのみ **CANDIDATE**（Security identity resolved ではない）

→ Request provenance は満たされた。次は Join Candidate / SecurityId / DailyPrice へ進まず、binding candidate の運用境界を維持する（§16）。

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
| **`ProviderSymbolBindingEvidence`** | provider symbol ↔ external id の **binding**（文字列一致 alone 禁止） |
| share-class evidence | 複数 class があり得る場合の必須分離 |
| venue evidence | dual-list / listing 曖昧性がある場合の必須分離（`exchCode` は補助、MIC モデルは未整備） |
| `mappingEligibilityBoundaryAt` | mapping evidence の forward 境界 |
| `bindingEligibilityBoundaryAt` | binding evidence が独立時刻を持つ場合の forward 境界 |
| ambiguity count == 0（Fail-Closed） | 複数 FIGI / overlap / conflict で自動確定禁止 |
| trading currency evidence（DailyPrice 用） | **identity / SecurityId 発行とは別**；明示 trading currency のみ |

**概念上の最小 join candidate（実装禁止・文書概念のみ）:**

```text
PriceSecurityJoinCandidate
  priceArchiveId
  mappingArchiveId
  bindingEvidenceId?      // ProviderSymbolBindingEvidence
  providerSymbol          // provenance only
  externalIdentifierNamespace
  externalIdentifier
  venueEvidence?          // exchCode / future MIC
  shareClassEvidence?
  tradingCurrencyEvidence?  // DailyPrice path only
  joinEligibleAt
  status                  // RESOLVED | AMBIGUOUS | MISSING_* | INELIGIBLE_TIME | REVISION_CONFLICT | FORBIDDEN_BACKFILL
```

過剰な production class / DB schema は作らない。

---

## 5. `joinEligibleAt` (forward only)

Price / mapping / binding の各 evidence が揃うまで join 可能扱いにしない。

binding evidence が独立時刻を持つ場合:

```text
joinEligibleAt =
  max(
    priceEligibilityBoundaryAt,
    mappingEligibilityBoundaryAt,
    bindingEligibilityBoundaryAt
  )
```

binding が mapping archive と同一 eligibility に内包される設計でも、**欠落した binding を price/mapping の時刻だけで補完しない**。

制約:

- `joinEligibleAt >= priceEligibilityBoundaryAt`
- `joinEligibleAt >= mappingEligibilityBoundaryAt`
- `joinEligibleAt >= bindingEligibilityBoundaryAt`（独立時刻がある場合）
- **`joinEligibleAt` / `bindingEligibilityBoundaryAt` を historical `knownAt` と呼ばない**
- binding 時刻の past backfill / retrospective 遡及 **禁止**
- `validFrom` / tickerStartDate / marketDate / Last Refreshed から knownAt / joinEligibleAt / bindingEligibilityBoundaryAt を生成しない

### Time-direction QA

| Scenario | Allowed interpretation |
| --- | --- |
| 09:00 Price OBSERVED、10:00 Mapping OBSERVED | 09:00 時点で identity 既知だった扱い **禁止**。join 可能は **10:00 以降**（binding も揃っている前提） |
| 09:00 Mapping OBSERVED、16:30 Price OBSERVED | join 可能は **16:30 以降** |
| binding evidence が更に遅い | `joinEligibleAt` は **binding 側**まで遅らせる |
| current mapping を old price へ backfill | **FORBIDDEN** |
| AV symbol と OpenFIGI ticker 文字列一致のみで過去へ結ぶ | **FORBIDDEN**（binding evidence 不足） |

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

1. provider symbol ↔ external id の **binding evidence** が成立（文字列一致 alone 禁止）
2. stable external id + namespace が一意（例: `figi` + 単一 FIGI）
3. security / share-class 粒度が明確（必要なら `shareClassFIGI` 等）
4. listing/venue 粒度が用途上必要なら充足（dual-list では MIC/venue MUST）
5. validity / ticker history が用途上必要なら充足（current snapshot の過去逆適用禁止）
6. ambiguity count = 0 / revision conflict なし
7. forward provenance あり（mapping archive OBSERVED + eligibility / binding boundary）
8. Issuer 関係は **別ゲート**（自動で Issuer から単一 Security を選ばない）

### SecurityId NO-GO 理由（identity 側のみ）

**現時点 SecurityId issuance = NO-GO。理由は identity 側に限定する:**

- provider symbol ↔ OpenFIGI external id の **binding evidence 未成立**（request input 再現不可）
- venue / listing 粒度不足（正式 MIC モデルなし；dual-list リスク）
- share-class 粒度不足（必須モデルなし）
- validity / ticker history 不足
- ambiguity / revision の意味的解決ルール未整備

**trading currency は SecurityId issuance blocker に含めない。**  
trading currency は **DailyPrice mapping blocker のみ**（§8）。

---

## 8. DailyPrice mapping gate (document only)

`DailyPrice` 開始に必要なもの（現行型の必須項目と一致）:

| Requirement | Current state |
| --- | --- |
| resolved `SecurityId` | NO-GO（§7 identity 側） |
| explicit **trading** currency | UNRESOLVED（AV daily / OpenFIGI とも不足）→ **DailyPrice 専用 blocker** |
| raw OHLCV validation | PRICE archive validation は possession 用に存在。domain mapping は未着手 |
| forward eligibility | archive 境界は存在 |
| raw vs adjusted 境界 | TIME_SERIES_DAILY only（adjusted 混在禁止維持） |
| historical `knownAt` | UNRESOLVED / UNUSABLE（Backtest 用）。Forward research でも `DailyPrice.knownAt` を捏造しない |

**責務分離:**

- SecurityId issuance ↔ identity / binding / venue / class / validity / ambiguity
- DailyPrice mapping ↔ SecurityId 解決 **後**の trading currency + OHLCV + eligibility

**DailyPrice mapping = NO-GO**。

---

## 9. Acceptance matrix

| Case | Inputs | Verdict |
| --- | --- | --- |
| 1 | symbol only | **FAIL**（identity / DailyPrice とも） |
| 2 | symbol + unique FIGI, venue unknown | Identity **候補のみ（PARTIAL）**。SecurityId issuance **NO-GO**（dual-list/class リスク未遮断）。DailyPrice **NO-GO** |
| 3 | unique FIGI + share class + venue, currency unknown | Security **identity 候補は前進可**（binding 成立前提）。SecurityId issuance は履歴/revision 不足でなお **NO-GO（厳格）**。DailyPrice **NO-GO**（currency blocker） |
| 4 | identity + venue + **trading currency** + 両 archive OBSERVED + binding | Forward join **候補**（`joinEligibleAt=max(price, mapping, binding)`）。なお historical knownAt / CA PIT が無い限り Real Backtest は NO-GO |
| 5 | mapping が Price より後 | `joinEligibleAt` は **後側**。Price 時刻への遡及禁止 |
| 6 | 複数 FIGI | **AMBIGUOUS**（自動確定禁止） |
| 7 | current mapping → old price | **FORBIDDEN** |
| 8 | ticker reuse | **FORBIDDEN / UNRESOLVED** |
| 9 | AV symbol と OpenFIGI ticker **文字列一致のみ**（request input / binding なし） | **FAIL / binding evidence 不足**。join 禁止。SecurityId 自動発行なし |
| 10 | OpenFIGI mapping request input 明示 + one candidate + venue 等明示 | **forward binding candidate** 可。ただし **SecurityId 自動発行なし**。DailyPrice は currency 等で別判定 |

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
| G | 次の最小作業 | **Forward Security Mapping / Join Candidate 境界の維持的拡張はまだ禁止**。本 PR は `ProviderSymbolBindingEvidence` candidate のみ。SecurityId / DailyPrice / PriceSecurityJoinCandidate は含めない |

---

## 12. Remaining risks

### Critical

1. ~~provider symbol ↔ OpenFIGI external id の **binding / request provenance 不足**~~ → PR #22 + ProviderSymbolBindingEvidence candidate で **request provenance は充足**。ただし SecurityId 確定 join は未開放（PARTIAL 維持）  
2. Trading currency evidence 不在 → **DailyPrice 不可**（SecurityId issuance とは分離）  
3. Historical knownAt / retrospective Security Master 不在 → Real Backtest NO-GO  
4. CA PIT / survivorship / universe entitlement 等の既存 Critical（本 Gate で解消しない）  
5. ticker-only / string-match-only / current-mapping backfill を許せば identity 破壊（禁止維持が必須）

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

### 再判定結果（PR #22 反映後）

OpenFIGI request-side provenance は **main で充足**:

- response raw と request raw（`request/{archiveId}.request.raw`）を immutable 保存
- `requestPayloadHash` / `requestPayloadUri` と `requestKey` body hash が整合
- 後から `idType` / `idValue` / `exchCode` を Fail-Closed に再検証可能
- `ProviderSymbolBindingEvidence` は PRICE requestKey 由来 symbol と OpenFIGI TICKER request の provenance 結合として **CANDIDATE** まで導出可能

**まだ開かないもの:** Security identity resolved、SecurityId 自動発行、PriceSecurityJoinCandidate、DailyPrice、currency 推測、MIC、Backtest

**次の最小作業（1つ）:** **Alpha Vantage Price Venue Semantics Gate**（公式 AV 資料のみの文書調査。`TIME_SERIES_DAILY` symbol / exchange / consolidated semantics）。詳細は [`venue-listing-identity-gate-review.md`](venue-listing-identity-gate-review.md)。  
**含めない:** AV client 実装、SecurityId 自動発行、DailyPrice mapping、MIC mapper、current mapping past backfill、Android UI

その他の次点:

- MIC mapping evidence PoC（OpenFIGI `micCode` request provenance；AV semantics の後）  
- ShareClassEvidence PoC  
- Trading Currency source PoC（DailyPrice 専用）  
- SecurityId issuance boundary（文書＋将来実装）

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
