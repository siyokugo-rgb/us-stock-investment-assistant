# Security Identity Continuity Evidence PoC

**Date (UTC):** 2026-09-18  
**Baseline `origin/main` HEAD:** `cabeee533449a18ca74ea8ef9235b49049aa06cb`  
**Baseline `origin/main` tree:** `25d243447b27cf0e6439e54ce45f758bf5163b57`  
**Gate SoT:** [`security-identity-ticker-reuse-gate.md`](security-identity-ticker-reuse-gate.md)  
**PR #40:** MERGED（Gate Review）  
**Mode:** synthetic PASS + real multi-as-of **LIVE_VERIFIED**（いずれも SecurityId / knownAt / validity / DailyPrice / Backtest の GO ではない）

| Gate | Status |
| --- | --- |
| Same-source continuity evidence deriver（Overview↔Overview / All Tickers↔All Tickers） | **PASS（本 PoC・synthetic）** |
| Cross-source Overview↔All Tickers auto-join | **NO-GO（今回禁止）** |
| SecurityId issuance | **NO-GO** |
| SecurityIdentifier / knownAt / validFrom / validTo | **NO-GO** |
| DailyPrice.currency / MIC / Venue | **NO-GO** |
| Real multi-as-of provider validation | **LIVE_VERIFIED**（AAPL/MSFT × T1/T2；下記） |
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
| Synthetic Fail-Closed / candidate rules | **PASS（PR #41）** |
| Real provider multi-as-of archive continuity | **LIVE_VERIFIED**（下記 Latest live attempt；mock 禁止・追加 live request 不要） |

### Live runner

- Entry: `archive.poc.binding.MassiveSecurityIdentityContinuityLivePoc`
- Gradle: `./gradlew --no-daemon -q massiveSecurityIdentityContinuityLivePoc`
- Reuses: `MassiveTickerOverviewForwardArchiveService` + `SecurityIdentityContinuityEvidenceDeriver`（規則変更なし）
- Tickers（無料枠最小）: `AAPL`, `MSFT`（`LIVE_VERIFIED` は identity-evaluable ≥2 のため 2 銘柄で足りる）
- Provider as-of dates: `T1=2025-01-06`, `T2=2026-06-01`（無料枠履歴範囲想定；`date=` selector only；≠ knownAt / validFrom / validTo）
- ARCHIVE_ROOT: `./archive-runtime`（gitignore；**Git 未保存**；fixture 化なし）
- Max **4** HTTP requests（2×2）；自動 retry / 大量 request 禁止；429 → provider failure 記録

### Latest live attempt

| Field | Value |
| --- | --- |
| Run context | Free-tier live（AAPL/MSFT × provider as-of `2025-01-06` / `2026-06-01`；max 4 requests；1 回のみ） |
| `MASSIVE_API_KEY` | **SET**（値は記録しない） |
| Overall | **LIVE_VERIFIED** |
| identityEvaluablePairs | **2** |
| integrityFail | **false** |
| 403 / 429 / provider failure | **なし** |
| AAPL observationStatus | T1=`OBSERVED`（2025-01-06） / T2=`OBSERVED`（2026-06-01） |
| AAPL share_class_figi | `BBG001S5N8V8` / `BBG001S5N8V8` |
| AAPL composite_figi | `BBG000B9XRY4` / `BBG000B9XRY4` |
| AAPL primary_exchange | `XNAS` / `XNAS` |
| AAPL derivedStatus / reason | `CONTINUITY_CANDIDATE` / `null` |
| AAPL evidenceEligibleAt | `2026-09-18T05:39:45.787697068Z`（今回 archive availability；≠ provider as-of） |
| AAPL integrityFail | **false** |
| MSFT observationStatus | T1=`OBSERVED`（2025-01-06） / T2=`OBSERVED`（2026-06-01） |
| MSFT share_class_figi | `BBG001S5TD05` / `BBG001S5TD05` |
| MSFT composite_figi | `BBG000BPH459` / `BBG000BPH459` |
| MSFT primary_exchange | `XNAS` / `XNAS` |
| MSFT derivedStatus / reason | `CONTINUITY_CANDIDATE` / `null` |
| MSFT evidenceEligibleAt | `2026-09-18T05:39:45.865939451Z`（今回 archive availability；≠ provider as-of） |
| MSFT integrityFail | **false** |
| live raw / manifest | **Git 未収録**（`archive-runtime` gitignored） |
| provider as-of ≠ knownAt | **維持**（T1/T2 は provider `date=` のみ；`evidenceEligibleAt` へ遡及しない） |
| SecurityId / SecurityIdentifier / knownAt / validFrom / validTo / DailyPrice / MIC / Venue / Real Backtest | **NO-GO 維持** |
| Paid API plan | **不要**（無料利用前提） |

**`LIVE_VERIFIED` が証明すること（これだけ）:**  
実 Massive の複数 provider as-of archive を既存 Fail-Closed deriver へ通し、identity continuity candidate を再現できた。

**`LIVE_VERIFIED` が証明しないこと（GO にしない）:**  
SecurityId issuance / SecurityIdentifier / knownAt / validFrom·validTo / identity validity interval / DailyPrice.currency / Real Backtest / MIC·Venue。  
特に `T1=2025-01-06`・`T2=2026-06-01` は **provider as-of**；`evidenceEligibleAt`（2026-09-18）は **今回の archive availability**。provider as-of を knownAt へ遡及禁止。

| Classification | Meaning |
| --- | --- |
| `LIVE_VERIFIED` | ≥2 **identity-evaluable** pairs（両 OBSERVED + 両 `share_class_figi` nonblank + deriver 完了 + status ≠ UNRESOLVED + integrity PASS）。provider 到達だけでは足りない |
| `LIVE_PARTIAL` | provider 到達したが identity-evaluable pair が 0〜1、optional identity field 欠損等 |
| `LIVE_UNVERIFIED` | key 無し / 有効検証不可 |
| `LIVE_FAIL` | raw↔deriver 矛盾 / invariant 違反等 |

`LIVE_VERIFIED` は CONTINUITY_CANDIDATE 固定ではない（raw に従い RECYCLE / CONFLICT / TICKER_CHANGE も identity-evaluable 可）。  
`LIVE_PARTIAL` は失敗と同一視しないが、real multi-as-of Gate PASS にも昇格しない。

---

## Residual blockers

| Item | Severity |
| --- | --- |
| Real multi-as-of Massive archives での continuity 再現 | **解消（LIVE_VERIFIED）** — 上記 Latest live attempt；SecurityId / validity / Backtest の GO ではない |
| SecurityId 発行ポリシー不在（意図的） | **Critical**（NO-GO） |
| SecurityIdentifier namespace/granularity 不足 → FIGI 安全格納不可 | **High** |
| Ticker Events / change effective date / knownAt 未確立 | **High**（Semantics Gate + offline archive 済み；live probe 未） |
| Massive↔OpenFIGI FIGI 粒度整合の実装層なし | **High** |
| Cross-source Overview↔All Tickers continuity | **High**（別 Gate） |
| DailyPrice.currency / Real Backtest | **Critical**（NO-GO 維持） |

---

## Next（1 つのみ）

| Option | Select? |
| --- | --- |
| A. Re-run live Overview multi-as-of validation with MASSIVE_API_KEY set | **DONE**（`LIVE_VERIFIED`；追加 live request 不要） |
| B. Massive Ticker Events Semantics Gate Review | **DONE（PR #43）** → [`massive-ticker-events-gate.md`](massive-ticker-events-gate.md) |
| C. Ticker Events forward archive PoC | **DONE（PR #44）** → [`massive-ticker-events-forward-archive-poc.md`](massive-ticker-events-forward-archive-poc.md) |
| **D. Live Ticker Events schema probe / live archive validation** | **YES（次工程）** |
| E. Massive↔OpenFIGI FIGI consistency PoC | 後続 |
| F. Cross-source Overview↔All Tickers continuity Gate | 別軸 |
| G. SecurityId issuance 実装 | **禁止**（Critical NO-GO のまま） |

選定理由: Semantics Gate + offline forward archive 境界は完了。次は live schema probe。SecurityId 実装へ直接進まない。

---

## Related docs

- [`security-identity-ticker-reuse-gate.md`](security-identity-ticker-reuse-gate.md)
- [`massive-ticker-events-gate.md`](massive-ticker-events-gate.md)
- [`data-contract.md`](data-contract.md)
- [`massive-ticker-overview-forward-archive-poc.md`](massive-ticker-overview-forward-archive-poc.md)
- [`massive-all-tickers-forward-archive-poc.md`](massive-all-tickers-forward-archive-poc.md)
- [`trading-currency-evidence-poc.md`](trading-currency-evidence-poc.md)

---

## Merge advice

- Draft PR としてレビュー可
- **Ready / merge はユーザー指示まで禁止**
- 本文書は SecurityId 発行・SecurityIdentifier 行生成・DailyPrice・Real Backtest・ticker 文字列連続履歴の許可書ではない
- Contract PASS ≠ Evidence PASS ≠ Continuity GO；**`LIVE_VERIFIED` ≠ SecurityId / knownAt / validity / DailyPrice / Backtest GO**
- live raw / `archive-runtime` は Git に入れない；API key 値は文書・diff に載せない
