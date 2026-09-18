# Security Identity Continuity Evidence PoC

**Date (UTC):** 2026-09-18  
**Baseline `origin/main` HEAD:** `cabeee533449a18ca74ea8ef9235b49049aa06cb`  
**Baseline `origin/main` tree:** `25d243447b27cf0e6439e54ce45f758bf5163b57`  
**Gate SoT:** [`security-identity-ticker-reuse-gate.md`](security-identity-ticker-reuse-gate.md)  
**PR #40:** MERGED（Gate Review）  
**Mode:** synthetic-first（**synthetic PASS ≠ real multi-as-of provider PASS**）

| Gate | Status |
| --- | --- |
| Same-source continuity evidence deriver（Overview↔Overview / All Tickers↔All Tickers） | **PASS（本 PoC・synthetic）** |
| Cross-source Overview↔All Tickers auto-join | **NO-GO（今回禁止）** |
| SecurityId issuance | **NO-GO** |
| SecurityIdentifier / knownAt / validFrom / validTo | **NO-GO** |
| DailyPrice.currency / MIC / Venue | **NO-GO** |
| Real multi-as-of provider validation | **未実施** |
| Real Backtest | **NO-GO** |

---

## Purpose

異なる provider as-of snapshot（T1/T2）の Massive security/reference archive evidence を同一 source 内で比較し、Fail-Closed で次を導出する最小 PoC:

- `CONTINUITY_CANDIDATE`
- `TICKER_CHANGE_CANDIDATE`
- `RECYCLE_CANDIDATE`
- `UNRESOLVED`
- `CONFLICT`

**これは Security Master 本体ではない。** SecurityId / SecurityIdentifier / knownAt / validity を発行・生成しない。

---

## Scope

### Input sources（許可）

| Pair | Allowed |
| --- | --- |
| Massive Ticker Overview T1 ↔ Overview T2 | YES |
| Massive All Tickers T1 ↔ All Tickers T2 | YES（`ticker=` 必須 + `resultCount == 1`） |
| Overview ↔ All Tickers | **NO**（`SOURCE_PAIR_MISMATCH`） |

### Reused（書き換えなし）

- `ManifestRecord` / `ObservationStatus` / `Sha256Hex`
- `MassiveTickerOverviewRequestKey` / `MassiveTickerOverviewArchiveValidator`
- `MassiveAllTickersRequestKey` / `MassiveAllTickersArchiveValidator`

### Forbidden in this PoC

SecurityId / SecurityIdentifier / IdentifierType 変更 / namespace·granularity model / Ticker Events client / OpenFIGI consistency / MIC mapper / Venue / TradingCurrencyEvidence 変更 / DailyPrice / Backtest / Android / DB / live network 必須化 / 大規模 refactor / 既存 validator の PoC 都合改変。

---

## Model

Package: `archive.poc.binding`

- `SecurityIdentityContinuityEvidence`
- `SecurityIdentityContinuityEvidenceDeriver`
- `SecurityIdentityContinuityStatus` / `SecurityIdentityContinuityReason`

### Evidence fields（監査最低限）

`firstArchiveId` / `secondArchiveId` / `firstSource` / `secondSource` / first·second `providerTicker` / `providerAsOfDate` / `eligibilityBoundaryAt` / `evidenceEligibleAt` / `shareClassFigi` / `compositeFigi` / `primaryExchange` / `status` / `reason`

- **first / second:** presentation slots。**candidate のみ** deriver が first=earlier / second=later（provider as-of `date=`）へ正規化。date 欠落・same-as-of・SOURCE_* では archiveId 等の決定論的 slot（**temporal earlier/later を主張しない**）
- **firstSource / secondSource:** 両入力の source provenance（`SOURCE_PAIR_MISMATCH` でも両方保持；単一 `source` へ潰さない）
- **providerAsOfDate:** requestKey `date=` **のみ**（caller free-form date 禁止）
- **evidenceEligibleAt:** `max(firstEligibilityBoundaryAt, secondEligibilityBoundaryAt)`  
  → **derived evidence availability only**  
  ≠ identity `validFrom` / `validTo` / `knownAt` / ticker validity / SecurityIdentifier validity

### Candidate invariants（model `init` Fail-Closed）

| Status | 追加制約 |
| --- | --- |
| 全 candidate | `reason==null`；`firstArchiveId != secondArchiveId`；`firstSource == secondSource`；両 as-of non-null かつ `firstDate < secondDate`；両 share_class nonblank；両 eligibility non-null；`evidenceEligibleAt == max(eligibility)` |
| `CONTINUITY_CANDIDATE` | ticker 両 nonblank かつ一致；share_class 一致 |
| `TICKER_CHANGE_CANDIDATE` | ticker 両 nonblank かつ不一致；share_class 一致 |
| `RECYCLE_CANDIDATE` | ticker 両 nonblank かつ一致；share_class 不一致；両 composite nonblank かつ同一は **禁止**（Gate 上 CONFLICT） |
| `UNRESOLVED` / `CONFLICT` | `reason != null` |
---

## Status semantics

| Status | Meaning（candidate / Fail-Closed） |
| --- | --- |
| `CONTINUITY_CANDIDATE` | 同一 ticker + 両側 nonblank `share_class_figi` 一致 + 異なる as-of |
| `TICKER_CHANGE_CANDIDATE` | ticker OLD→NEW + `share_class_figi` 一致 + 異なる as-of（SecurityId 継続**確定ではない**） |
| `RECYCLE_CANDIDATE` | 同一 ticker + `share_class_figi` 不一致 + identity-layer conflict 無し（別 SecurityId **発行しない**） |
| `UNRESOLVED` | 欠損・非 cross-time・曖昧・無関係 pair 等 |
| `CONFLICT` | 同一比較内 identity layer 矛盾（latest-wins 禁止） |

### Decision matrix（要約）

| Condition | Result |
| --- | --- |
| same ticker + same share_class + T1&lt;T2 | CONTINUITY_CANDIDATE |
| ticker change + same share_class + T1&lt;T2 | TICKER_CHANGE_CANDIDATE |
| same ticker + different share_class（composite 不一致等・layer conflict 無し） | RECYCLE_CANDIDATE |
| share_class missing either side | UNRESOLVED `SHARE_CLASS_IDENTITY_MISSING` |
| date omitted either side | UNRESOLVED `PROVIDER_AS_OF_MISSING`（ingestedAt で順序補完しない） |
| same as-of + matching identity | UNRESOLVED `SAME_AS_OF_NOT_CROSS_TIME`（continuity 昇格禁止） |
| same as-of + conflicting share_class | CONFLICT |
| same composite + different nonblank share_class | CONFLICT `IDENTITY_LAYER_CONFLICT` |
| different ticker + different share_class | UNRESOLVED（無関係 pair を recycle にしない） |
| primary_exchange のみ変化 + share_class 一致 | CONTINUITY_CANDIDATE 維持（listing 変化と混同しない；MIC resolved 禁止） |
| non-OBSERVED / eligibility null | UNRESOLVED `INPUT_NOT_OBSERVED` |
| raw SHA mismatch / raw missing / malformed requestKey | **exception** Fail-Closed |
| All Tickers: no `ticker=` or `resultCount != 1` | UNRESOLVED `SNAPSHOT_AMBIGUOUS` |
| Overview↔All Tickers | UNRESOLVED `SOURCE_PAIR_MISMATCH` |

**composite_figi:** 補助 identity evidence のみ。share-class 代用で continuity 確定禁止。  
**primary_exchange:** provider-declared primary listing exchange ISO-code evidence。変化だけで Security change / recycle / conflict 自動判定禁止。  
**CIK:** continuity 判定に不使用（本 model に載せない）。

---

## provider as-of ≠ knownAt

時間順序は requestKey の `date=` **のみ**。

禁止転用: `ingestedAt` / `fetchedAt` / `eligibilityBoundaryAt` → T1/T2 provider 状態順序。

- date omitted → `UNRESOLVED` / `PROVIDER_AS_OF_MISSING`
- current/latest（date omitted）を古い dated snapshot より「後」と暗黙解釈しない
- 同一 as-of で identity 一致しても cross-time continuity へ昇格しない

---

## evidenceEligibleAt ≠ identity validity

`evidenceEligibleAt = max(first, second eligibility)` は **derived evidence が利用可能になる時刻**。

≠ Security identity `validFrom` / `validTo` / `knownAt` / ticker validity / SecurityIdentifier validity。

date omitted / INPUT_NOT_OBSERVED / SOURCE_* では first/second フィールド名から **時間順序を主張しない**（決定論的 presentation slot のみ）。

---

## Raw / manifest integrity

入力 `ManifestRecord` 最低条件:

- expected Massive domain/source
- `OBSERVED`
- `eligibilityBoundaryAt` non-null
- canonical requestKey
- `rawPayloadUri` / `rawPayloadHash`
- HTTP success contract

raw は **必ず** `ManifestRecord.rawPayloadUri` から読む（caller-supplied bytes 禁止）。  
SHA-256 再計算 ↔ manifest hash。mismatch / missing → `ArchiveValidationException`。  
既存 validator で再検証；OBSERVED でも raw revalidation 失敗なら candidate へ進めない。

### All Tickers 制約

`MassiveAllTickersValidationOutcome` の optional parallel lists は multi-row 欠損時に row alignment が identity 用途に不安全。

本 PoC: canonical requestKey に `ticker=` 必須 + `resultCount == 1` + ticker exact match。  
multi-row / filter 無し → continuity 判定に使わず `UNRESOLVED`（validator 構造変更で解決しない）。

---

## Tests

`SecurityIdentityContinuityEvidenceTest`（synthetic helpers / 小 fixture）:

1. same ticker + same share_class + T1&lt;T2 → CONTINUITY  
2. OLD→NEW + same share_class → TICKER_CHANGE  
3. same ticker + different share_class → RECYCLE  
4. share_class missing → UNRESOLVED  
5. date omitted → UNRESOLVED（ingest 順序なし；temporal earlier/later 偽装なし）  
6. same as-of matching → no continuity elevate  
7. same as-of conflicting → CONFLICT  
8. same composite + different share_class → CONFLICT  
9. different ticker + different share_class → UNRESOLVED  
10. primary_exchange only change → continuity candidate 維持  
11. non-OBSERVED → candidate 禁止  
12. raw SHA mismatch → Fail-Closed  
13. raw missing → Fail-Closed  
14. malformed requestKey → Fail-Closed  
15. Overview pair happy path  
16. All Tickers pair（filter + resultCount=1）  
17. All Tickers multi-row / no filter → UNRESOLVED  
18. 引数左右逆でも as-of 正規化後 first/second が同一  
19. evidenceEligibleAt = max(eligibility)  
20. model surface に SecurityId / SecurityIdentifier / knownAt / validFrom / validTo / DailyPrice / earlier* / later* 経路なし  
21. cross-source → 両 source 保持  
22. + manual construct invariant rejects（ticker/shareClass/source/eligibility/same-archive 等）

---

## Real-data validation status

| Claim | Status |
| --- | --- |
| Synthetic Fail-Closed / candidate rules | **PASS（本 PoC）** |
| Real provider multi-as-of archive continuity | **未実施** |

---

## Residual blockers

| Item | Severity |
| --- | --- |
| Real multi-as-of Massive archives での continuity 再現未実施 | **Critical** |
| SecurityId 発行ポリシー不在（意図的） | **Critical**（NO-GO） |
| SecurityIdentifier namespace/granularity 不足 → FIGI 安全格納不可 | **High** |
| Ticker Events / change effective date / knownAt 未確立 | **High** |
| Massive↔OpenFIGI FIGI 粒度整合の実装層なし | **High** |
| Cross-source Overview↔All Tickers continuity | **High**（別 Gate） |
| DailyPrice.currency / Real Backtest | **Critical**（NO-GO 維持） |

---

## Next（1 つのみ）

| Option | Select? |
| --- | --- |
| **A. Real multi-as-of Massive continuity evidence 検証（Overview dated snapshots）** | **YES（候補）** |
| B. Ticker Event archive PoC | 後続 |
| C. Massive↔OpenFIGI FIGI consistency PoC | A の後でも可 |
| D. Cross-source Overview↔All Tickers continuity Gate | 別軸 |

選定理由: synthetic Fail-Closed は固定済み。blocking は実 provider 複数 as-of での候補再現可否。

---

## Related docs

- [`security-identity-ticker-reuse-gate.md`](security-identity-ticker-reuse-gate.md)
- [`data-contract.md`](data-contract.md)
- [`massive-ticker-overview-forward-archive-poc.md`](massive-ticker-overview-forward-archive-poc.md)
- [`massive-all-tickers-forward-archive-poc.md`](massive-all-tickers-forward-archive-poc.md)
- [`trading-currency-evidence-poc.md`](trading-currency-evidence-poc.md)

---

## Merge advice

- Draft PR としてレビュー可
- **Ready / merge はユーザー指示まで禁止**
- 本文書は SecurityId 発行・SecurityIdentifier 行生成・DailyPrice・Real Backtest・ticker 文字列連続履歴の許可書ではない
- Contract PASS ≠ Evidence PASS ≠ Continuity GO；synthetic PASS ≠ real multi-as-of PASS
