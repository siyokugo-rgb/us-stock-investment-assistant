# Security Identity / Ticker-reuse Gate Review

**Date (UTC):** 2026-09-18
**Repository:** `siyokugo-rgb/us-stock-investment-assistant`
**Review type:** Gate Review / domain-data integrity audit only（実装なし）
**Baseline `origin/main` HEAD:** `696456f238cea44caa2169c33f6d27ae8987fadd`
**Prior MERGED:** PR #39（Trading Currency Temporal Applicability Gate）

| Gate | Verdict |
| --- | --- |
| Provider ticker join（同一 request / 同一 as-of snapshot） | **PASS（条件付き）** — Massive namespace case-sensitive exact |
| Cross-time Security continuity | **NO-GO / BLOCKING** — ticker 文字列一致だけでは証明不可 |
| Ticker-change handling（contract） | **PASS（契約固定）** — 同一 Security なら SecurityId 継続 |
| Ticker-change handling（evidence） | **PARTIAL / UNRESOLVED** — 実データで確定する経路は未完成 |
| Ticker-recycle handling（contract） | **PASS（契約固定）** — 別 SecurityId；文字列 join 禁止 |
| Ticker-recycle handling（evidence） | **PARTIAL / UNRESOLVED** — FIGI 変化は recycle **候補**；自動確定禁止 |
| Listing identity | **PARTIAL** — OpenFIGI venue-level `figi` candidate；`primary_exchange` は provider-declared ISO-code evidence（≠ MIC/listing resolved） |
| Share-class identity | **PARTIAL** — `shareClassFIGI` / `share_class_figi` evidence candidate |
| Internal SecurityId issuance | **NO-GO** |
| SecurityIdentifier validity periods | **NO-GO** — validity/knownAt 生成禁止；namespace/granularity 構造不足（§H.1） |
| Historical / PIT safety | **NO-GO** |
| Trading Currency temporal applicability | **UNRESOLVED 維持**（PR #39） |
| DailyPrice.currency | **NO-GO** |
| Real Backtest | **NO-GO** |

**本 Gate の中心問題:**

> 異なる時点で同じ Massive ticker 文字列を持つ record を、同一 Security の連続履歴として扱える条件は何か？

**本 Gate ではないもの:** domain contract の再設計。以下は **既存確定**（維持）:

- ticker → SecurityId **禁止**
- CIK → SecurityId **禁止**
- external id そのもの = SecurityId **禁止**
- Issuer ≠ Security；Issuer : Security = **1:N**
- ticker **change** → 同一 Security なら SecurityId **継続**
- ticker **recycle** → 別 SecurityId；文字列一致 join **禁止**
- current identifier の過去逆適用 **禁止**
- latest-wins / ambiguity 自動解決 **禁止**
- `validFrom` / `validTo` / `knownAt` / `ingestedAt` 混同 **禁止**
- Trading Currency と Security identity **分離**
- PR #39: ticker 文字列一致だけでは cross-time continuity **不可**

---

## 0. SoT audited

### Docs

- [`data-contract.md`](data-contract.md) §1（Issuer/Security/ticker change/recycle）
- [`security-master-acceptance-criteria.md`](security-master-acceptance-criteria.md)
- [`security-master-feasibility-poc.md`](security-master-feasibility-poc.md)
- [`venue-listing-identity-gate-review.md`](venue-listing-identity-gate-review.md)
- [`openfigi-forward-archive-poc.md`](openfigi-forward-archive-poc.md)
- [`massive-all-tickers-forward-archive-poc.md`](massive-all-tickers-forward-archive-poc.md)
- [`massive-ticker-overview-forward-archive-poc.md`](massive-ticker-overview-forward-archive-poc.md)
- [`massive-price-overview-binding-poc.md`](massive-price-overview-binding-poc.md)
- [`trading-currency-temporal-applicability-gate.md`](trading-currency-temporal-applicability-gate.md)

### Code

- `SecurityId` / `SecurityIdentifier` / `SecurityIdentifierIndex` / `IdentifierType`
- `IssuerId` / `IssuerSecurityRelation`
- Massive All Tickers / Overview requestKey・validator
- `ProviderSymbolBindingEvidence` / `MassivePriceReferenceBindingEvidence`

非公式ブログは identity 確定根拠に使わない。公式意味は既存 Gate/PoC に記録された範囲と repo 内注記に限定する。

---

## A. Identity 粒度の分離

混同禁止。

| Layer | Meaning（repo SoT） | Typical carrier today | ≠ |
| --- | --- | --- | --- |
| 1. Provider ticker | Massive/AV 等の表示シンボル | requestKey `ticker` / Overview ticker | SecurityId |
| 2. Listing / venue-level identity | instrument **per trading venue** | OpenFIGI response `figi`（equity venue-level） | share class；SecurityId |
| 3. Composite identity | country/market aggregate of venue FIGIs | OpenFIGI `compositeFIGI`；Massive `composite_figi` | venue listing；SecurityId |
| 4. Share-class identity | instrument class across markets | OpenFIGI `shareClassFIGI`；Massive `share_class_figi` | listing；ticker |
| 5. Issuer identity | 提出・発行主体 | `IssuerId`；CIK on Issuer side only | Security |
| 6. Internal SecurityId | アプリ内部 Security 主キー | `SecurityId`（未発行経路） | あらゆる外部文字列 |

### Field notes（既存 Gate 要約）

| Field | Granularity | Official/repo note | Forbidden use |
| --- | --- | --- | --- |
| OpenFIGI `figi` | listing / venue-level external identity **candidate** | venue Gate §5.1 | SecurityId；自動 MIC |
| `compositeFIGI` / `composite_figi` | composite / market aggregate | venue Gate §5.2；Massive Overview docs paraphrased in join-gate | venue-level FIGI と同一視；SecurityId |
| `shareClassFIGI` / `share_class_figi` | share-class aggregate | venue Gate §5.3；GOOG/GOOGL 潰し禁止 | listing FIGI；SecurityId |
| `primary_exchange` | Massive **provider-declared primary listing exchange ISO-code evidence**（raw/provider） | ≠ internal MIC resolved；≠ listing identity resolved | SecurityId；venue resolved；MIC resolved への昇格 |
| `cik` | Issuer / filing-entity side | `IdentifierType` に CIK 無し | SecurityId；SecurityIdentifier |
| OpenFIGI `exchCode` | OpenFIGI exchange code | ≠ ISO MIC | MIC 無根拠変換 |

---

## B. Cross-time continuity cases

**前提語彙（文書のみ・巨大 enum 禁止）:**

| Outcome | Meaning |
| --- | --- |
| CONTINUITY_CANDIDATE | 同一 Security 連続の **候補**（SecurityId 発行・確定ではない） |
| RECYCLE_CANDIDATE | 同 ticker 文字列が別 Security の可能性（別 SecurityId 契約側） |
| TICKER_CHANGE_CANDIDATE | 表示シンボル変更・同一 Security の可能性 |
| UNRESOLVED | 証拠不足；連続も recycle も断定しない |
| CONFLICT | 同条件で競合する identity evidence；自動採用禁止 |

| Case | Pattern | Continuity claim? | Outcome |
| --- | --- | --- | --- |
| 1 | date=T1 ticker=AAPL；date=T2 ticker=AAPL；**same nonblank** stable share-class（or agreed listing）evidence | 連続 **候補**まで | **CONTINUITY_CANDIDATE**（確定／SecurityId **不可**） |
| 2 | ticker OLD→NEW；stable share-class evidence **一致** | change 候補 | **TICKER_CHANGE_CANDIDATE**（SecurityId 継続は契約上の意味；evidence は候補） |
| 3 | ticker=RECY；identity A→B（例: share_class 変化） | 同一 Security **主張不可** | **RECYCLE_CANDIDATE** |
| 4 | ticker 一致；stable identity field **欠損** | 不可 | **UNRESOLVED** |
| 5 | ticker 一致 + **CIK 一致のみ** | 不可（Issuer≠Security；1:N） | **UNRESOLVED**（Issuer 側 corroboration に留められる） |
| 6 | share-class 一致；listing/venue 異なる | Security 連続 ≠ listing 同一 | share-class **CANDIDATE**；listing continuity **別軸**（混同禁止） |
| 7 | composite 一致；share-class **不一致/欠損** | 短絡禁止 | **UNRESOLVED** or **CONFLICT**（矛盾時） |
| 8 | 同一 snapshot 内 identity conflict | 不可 | **CONFLICT** |

**Case 1 の注意:**  
「stable identity 一致」でも as-of snapshot の一致であり、`[T1,T2)` の無欠落 continuity や knownAt は証明しない（欠落区間は §E of Temporal Gate と同様に補間禁止）。

---

## C. Stable external ID ≠ SecurityId 発行

「FIGI が同じなら SecurityId を発行してよい」は **短絡禁止**。

### External identity **candidate** 成立に最低限見るもの

| Check | Why |
| --- | --- |
| identifier namespace | `figi` vs Massive composite/share_class 文字列を混ぜない |
| 粒度 | listing / composite / share-class を取り違えない |
| share class | GOOG/GOOGL 等の潰し禁止 |
| listing / venue | `primary_exchange` は provider-declared ISO-code evidence；MIC/listing resolved とみなさない |
| provenance | requestKey / raw hash / OBSERVED |
| provider as-of | `date=` selector；≠ knownAt |
| evidence availability | `eligibilityBoundaryAt` / bindingEligibleAt；≠ validFrom |
| ambiguity | 複数候補は全保持（`SecurityIdentifierIndex` と同方針） |
| missing / conflicting values | Fail-Closed（§F） |

### Internal SecurityId 発行

| Item | Verdict |
| --- | --- |
| 本 Gate で発行条件を「実装してよい」とするか | **NO-GO** |
| 外部 identity candidate PASS | SecurityId 発行を **許可しない** |
| 発行に追加で必要なもの（不足） | 明示的 master 採用ポリシー、PIT/`knownAt`、conflict 解決、人間/上位 Fail-Closed 規則 |

外部 candidate と内部 SecurityId は **常に分離**。

---

## D. Ticker Events

| Item | Repo fact |
| --- | --- |
| Feasibility 言及 | `security-master-feasibility-poc.md` — Massive Ticker Events = ticker history **PARTIAL** |
| Semantics Gate | [`massive-ticker-events-gate.md`](massive-ticker-events-gate.md)（公式一次資料；experimental；live なし） |
| 実装 | **無し**（client / archive / validator / test なし） |
| 公式 old/new/effective の本 repo 突合 | Semantics Gate で公式 sample まで固定；明示 `old_ticker`/`new_ticker` field **無し** |

### 仮に今後 archive する場合の禁止（先取り固定）

| Claim | Verdict |
| --- | --- |
| event/effective date → `knownAt` | **禁止** |
| event だけで Security continuity **確定** | **不可**（FIGI/share-class 等との接続が要る） |
| event を ticker-change **evidence candidate** に使う | **CONDITIONAL**（Semantics Gate；archive PoC 後に再評価） |

**現状 verdict:** Ticker Events = **PARTIAL / UNRESOLVED**（Semantics Gate 済み；evidence archive 未取得）。

---

## E. Ticker recycle（最重要）

**禁止:** 「同じ ticker 文字列だから同じ Security」。

### 現行無料/既存 evidence で recycle **候補**を立てる材料

| Signal | Use as |
| --- | --- |
| same ticker + **different** nonblank `share_class_figi` across as-of | RECYCLE_CANDIDATE（強） |
| same ticker + **different** nonblank venue-level OpenFIGI `figi`（comparable namespace） | RECYCLE_CANDIDATE（listing 粒度） |
| same ticker + **different** nonblank `composite_figi` with share-class missing | UNRESOLVED〜RECYCLE_CANDIDATE（粒度不足なら UNRESOLVED） |
| delist gap / `list_date` gap alone | **単独では recycle 確定不可**（形式証拠；validTo 転用禁止） |
| issuer CIK change alone | Issuer 変化の hint；Security recycle **自動確定禁止** |

### 自動ルール化の限界

「どれか1フィールドが違う ⇒ 即 別 SecurityId」は **公式意味を確認せず作らない**。  
本 Gate が固定するのは:

1. 文字列一致 join **禁止**
2. stable identity **不一致** ⇒ 同一 Security continuity **主張不可**（RECYCLE_CANDIDATE / UNRESOLVED）
3. stable identity **一致** ⇒ CONTINUITY_CANDIDATE まで（発行・確定・区間補間なし）
4. 欠損 ⇒ UNRESOLVED（latest-wins なし）

---

## F. Missing / conflict（Fail-Closed）

| Pattern | Treatment |
| --- | --- |
| ticker 一致 / FIGI（該当粒度）欠損 | **UNRESOLVED** |
| composite 一致 / share_class 欠損 | share-class 断定 **禁止**；UNRESOLVED |
| Massive vs OpenFIGI FIGI conflict（同意図粒度） | **CONFLICT**；自動採用禁止 |
| CIK 一致 / FIGI 違い | Issuer corroboration ≠ Security 解決；UNRESOLVED or CONFLICT |
| same ticker / overlapping competing identities | **CONFLICT**；候補全保持 |
| ambiguity | latest-wins **禁止**；単一が必要なら Fail-Closed |

---

## G. PIT / Temporal 接続（PR #39 維持）

Identity continuity が将来解決しても:

| Claim | Verdict |
| --- | --- |
| provider as-of = knownAt | **NO** |
| evidenceEligibleAt = identity validFrom | **NO** |
| Historical backtest GO | **NO** |
| currency validity interval GO | **NO** |
| DailyPrice.currency GO | **NO** |

Security identity Gate と PIT / Temporal Gate を混ぜない。

---

## H. `SecurityIdentifier` mapping 可否

### H.1 現行モデル limitation（High・今回コード修正なし）

現行 `SecurityIdentifier`:

- `securityId`, `type`, `value`, `validFrom`, `validTo`, `knownAt`, `ingestedAt`, `source`

現行 `IdentifierType`:

- `TICKER`
- `VENDOR_PERMANENT_ID`

一方 [`security-master-acceptance-criteria.md`](security-master-acceptance-criteria.md) は external identifier に **namespace** と **granularity** を要求する。

したがって現行モデルでは、次を安全に区別して `SecurityIdentifier` へ格納する構造が **不足**:

- venue-level FIGI
- composite FIGI
- share-class FIGI

| Rule | Verdict |
| --- | --- |
| 本 Gate で `SecurityIdentifier` / `IdentifierType` をコード修正するか | **しない** |
| `SecurityIdentifier` mapping / 行生成 | **NO-GO（維持）** |
| 次の continuity evidence PoC で `SecurityIdentifier` を生成するか | **しない** |
| `source` 文字列へ namespace/granularity を埋め込み、business logic で parse する回避策 | **禁止** |
| 将来 SecurityId issuance / identifier adoption Gate で必要性を再評価するか | **YES** |

### H.2 個別 mapping 案

現行モデル必須: `securityId`, `type`, `value`, `validFrom`, `validTo?`, `knownAt`, `ingestedAt`, `source`。

| Mapping idea | Gate verdict |
| --- | --- |
| Massive/OpenFIGI ticker → `type=TICKER` value | 値の **候補**にはなり得るが SecurityId 紐付け不可のため **行生成 NO-GO** |
| FIGI → `VENDOR_PERMANENT_ID` | namespace/granularity 構造不足のまま生成 **禁止**（§H.1）；SecurityId も無い |
| Overview `list_date` → ticker `validFrom` | **禁止**（Overview PoC 明記） |
| `delisted_utc` → ticker `validTo` | **禁止**（形式確認のみ） |
| Ticker Event effective → identifier boundary | **未実装**；knownAt 転用禁止；採用は別 Gate |
| API `date=` → `knownAt` | **禁止** |
| archive `ingestedAt` → `SecurityIdentifier.ingestedAt` | possession としては近いが、単独で master 行を正当化しない |
| archive から `knownAt` 生成 | **禁止**（現行 Massive/OpenFIGI retail path で historical knownAt FAIL） |

**結論:** 現行 evidence から完全な `SecurityIdentifier` 行は **生成禁止**。次 PoC も生成しない。

---

## I. Acceptance Matrix

| # | Scenario | Verdict |
| --- | --- | --- |
| 1 | same ticker + same stable identity（非blank・同粒度） | CONTINUITY_CANDIDATE；SecurityId **NO**；区間補間 **NO** |
| 2 | same ticker + changed stable identity | RECYCLE_CANDIDATE；同一 Security **主張不可** |
| 3 | ticker changed + same share-class identity | TICKER_CHANGE_CANDIDATE；契約上 SecurityId 継続の **型**；evidence 確定は未 |
| 4 | recycled ticker（文字列再出 + identity 変化） | 別 Security **候補**；文字列 join **禁止** |
| 5 | identity field missing | **UNRESOLVED** |
| 6 | conflicting FIGI（同粒度） | **CONFLICT** |
| 7 | same issuer (CIK) / different share class | 別 Security **側**；CIK で潰さない |
| 8 | same share class / different listing | share-class link **可候補**；listing identity は別 |
| 9 | historical as-of fetched later | as-of evidence 可；knownAt **不可**（PR #39） |
| 10 | current identity → old bar backfill | **禁止** |
| 11 | delisted then ticker reused | recycle リスク **Critical**；delist 単独で確定しない |
| 12 | overlapping ambiguous candidates | 全保持；単一強制なら Fail-Closed |

---

## Separated verdicts（必須）

| Gate | Verdict | Notes |
| --- | --- | --- |
| Provider ticker join | **CONDITIONAL PASS** | 同一 as-of / 同一 provider namespace の exact match のみ |
| Cross-time Security continuity | **NO-GO（現状）** | 文字列一致不可；FIGI 一致でも CANDIDATE 止まり・欠落補間不可 |
| Ticker-change handling | **Contract PASS / Evidence PARTIAL** | 契約は固定；Semantics Gate 済み；archive 未 |
| Ticker-recycle handling | **Contract PASS / Evidence PARTIAL** | 契約は固定；FIGI 変化は候補 |
| Listing identity | **PARTIAL** | OpenFIGI venue-level `figi` candidate；Massive `primary_exchange` は provider-declared ISO-code evidence（≠ MIC/listing resolved） |
| Share-class identity | **PARTIAL** | `share_class_figi` / `shareClassFIGI` |
| Internal SecurityId issuance | **NO-GO** | |
| SecurityIdentifier validity periods | **NO-GO** | list_date/delisted/date→valid*/knownAt 禁止；namespace/granularity 構造不足（§H.1） |
| Historical / PIT safety | **NO-GO** | |
| Trading Currency temporal applicability | **UNRESOLVED 維持** | PR #39 |
| DailyPrice.currency | **NO-GO** | |
| Real Backtest | **NO-GO** | |

---

## Residual blockers

| Item | Severity |
| --- | --- |
| Cross-time continuity を ticker だけで扱えない | **Critical**（本 Gate 確認） |
| `SecurityIdentifier.knownAt` / validity を現行 archive から生成不可 | **Critical** |
| Ticker recycle の確定手順が候補段階 | **Critical** |
| 現行 `SecurityIdentifier` に namespace / granularity が無く venue/composite/share-class FIGI を安全格納できない | **High**（今回コード修正なし；mapping NO-GO 維持；source 埋め込み回避禁止） |
| Ticker Events live schema probe・knownAt 未確立 | **High**（live schema PASS；knownAt 転用禁止；[`massive-ticker-events-forward-archive-poc.md`](massive-ticker-events-forward-archive-poc.md)） |
| Massive vs OpenFIGI FIGI 粒度整合 / conflict 規則の実装層なし | **High** |
| Listing MIC ↔ price venue semantics | **High**（独立；本 Gate 非解決） |
| SecurityId 発行ポリシー不在 | **High**（意図的 NO-GO） |

---

## Next（1 つのみ）

| Option | Select? |
| --- | --- |
| A. Security identity continuity evidence 最小 PoC（synthetic-first） | **DONE** → [`security-identity-continuity-evidence-poc.md`](security-identity-continuity-evidence-poc.md) |
| B. Real multi-as-of Massive continuity evidence 検証（Overview dated snapshots） | **DONE / LIVE_VERIFIED**（PR #42） |
| C. Massive Ticker Events Semantics Gate Review | **DONE（PR #43）** → [`massive-ticker-events-gate.md`](massive-ticker-events-gate.md) |
| D. Ticker Events forward archive PoC | **DONE（PR #44）** → [`massive-ticker-events-forward-archive-poc.md`](massive-ticker-events-forward-archive-poc.md) |
| E. Live Ticker Events schema probe / live archive validation | **DONE**（`LIVE_SCHEMA_VERIFIED`） |
| **F. Ticker Events ↔ Overview share_class corroboration PoC** | **YES（次工程）** |
| G. FIGI consistency PoC（Massive↔OpenFIGI） | 後続 |
| H. SecurityId issuance 実装 | **禁止** |

**B（完了）:** 実 Massive 複数 provider as-of archive を既存 Fail-Closed deriver へ通し identity continuity candidate を再現（`LIVE_VERIFIED`）。SecurityId / knownAt / validity / DailyPrice / Backtest の GO ではない。

**C（完了）:** 公式一次資料で Ticker Events semantics / 時間意味 / acceptance を固定。

**D（完了）:** experimental Ticker Events の offline raw/validator/archive 境界。

**E（完了）:** XYZ × 1 live request；OBSERVED + integrity；SecurityId / knownAt / continuity なし。

**F 選定理由:** live schema 済み。次は Gate 組み合わせ A の候補 corroboration。SecurityId 実装へ直接進まない。

---

## Related docs

- [`data-contract.md`](data-contract.md)
- [`security-master-acceptance-criteria.md`](security-master-acceptance-criteria.md)
- [`security-master-feasibility-poc.md`](security-master-feasibility-poc.md)
- [`security-identity-continuity-evidence-poc.md`](security-identity-continuity-evidence-poc.md)
- [`massive-ticker-events-gate.md`](massive-ticker-events-gate.md)
- [`venue-listing-identity-gate-review.md`](venue-listing-identity-gate-review.md)
- [`trading-currency-temporal-applicability-gate.md`](trading-currency-temporal-applicability-gate.md)
- [`openfigi-forward-archive-poc.md`](openfigi-forward-archive-poc.md)
- [`massive-all-tickers-forward-archive-poc.md`](massive-all-tickers-forward-archive-poc.md)
- [`massive-ticker-overview-forward-archive-poc.md`](massive-ticker-overview-forward-archive-poc.md)

---

## Merge advice

- Draft PR としてレビュー可
- **Ready / merge はユーザー指示まで禁止**（本依頼範囲）
- 本文書は SecurityId 発行・SecurityIdentifier 行生成・DailyPrice・Real Backtest・ticker 文字列連続履歴の許可書ではない
- Contract PASS ≠ Evidence PASS ≠ Continuity GO
