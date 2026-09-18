# Trading Currency Temporal Applicability Gate

**Date (UTC):** 2026-09-18  
**Repository:** `siyokugo-rgb/us-stock-investment-assistant`  
**Review type:** Gate Review / domain-data audit only（実装なし）  
**Baseline `origin/main` HEAD:** `93d44bbcf08c74ff478ef9cd89ef5dcd1de47787`  
**Baseline `origin/main` tree:** `2694c67cde2d68e935491c27fb4d37cbce81746f`  
**Prior MERGED:** PR #38（PR #35 final content clean reapply）  
**Android Runtime Gate:** PASS（診断経路で `ANDROID CORE SMOKE: PASS`）

| Gate | Verdict |
| --- | --- |
| Archive-level currency evidence | **PASS（維持）** — Layer B CANDIDATE 生成済み |
| Forward applicability | **CONDITIONAL** — `evidenceEligibleAt` 以降の archive-level Forward のみ；bar 適用なし |
| Historical / bar-level applicability | **NO-GO** |
| DailyPrice.currency | **NO-GO** |
| Real Backtest | **NO-GO** |
| Security identity dependency | **BLOCKING** — ticker 文字列一致 ≠ Security 連続履歴 |

**SoT（本 Gate の読取対象）:**

- `TradingCurrencyEvidence.kt` / `TradingCurrencyEvidenceDeriver.kt` / `TradingCurrencyEvidenceTest.kt`
- [`trading-currency-evidence-poc.md`](trading-currency-evidence-poc.md)
- [`massive-currency-evidence-gate.md`](massive-currency-evidence-gate.md)
- `ManifestRecord.eligibilityBoundaryAt` 契約
- `MassiveAllTickersRequestKey` date semantics
- [`massive-all-tickers-forward-archive-poc.md`](massive-all-tickers-forward-archive-poc.md)

**明示的にしないこと（本 Gate）:** DailyPrice / DailyPrice.currency 追加、SecurityId / MIC / Venue 実装、過去 bar currency backfill、`temporalApplicability=RESOLVED` 化、latest-wins、コード/テスト変更。

---

## 0. Confirmed current model（facts）

| Fact | Source |
| --- | --- |
| Archive-level evidence only | `TradingCurrencyEvidence` KDoc / PoC |
| `temporalApplicability` = **UNRESOLVED only**（CANDIDATE でも固定） | model `init` + Deriver |
| `evidenceEligibleAt = max(priceEligibilityBoundaryAt, allTickersEligibilityBoundaryAt)` | model invariant |
| `allTickersRequestDate` = provider as-of selector from requestKey；nullable | Deriver + `MassiveAllTickersRequestKey` |
| `allTickersRequestDate ≠ knownAt` | PoC / Gate SoT |
| `allTickersRequestDate ≠ evidenceEligibleAt` | PoC |
| `allTickersRequestDate ≠ PRICE bar trading date` | PoC / currency Gate |
| CANDIDATE ≠ DailyPrice.currency usable | model KDoc |
| Pair-wise derive only；`CONFLICT` status reserved；latest-wins forbidden | model + Deriver |
| All Tickers OBSERVED → `eligibilityBoundaryAt = ingestedAt` | All Tickers forward PoC |
| `date=` optional `YYYY-MM-DD` provider as-of；canonical requestKey | `MassiveAllTickersRequestKey` |

---

## A. 時間軸の分離（必須）

混同禁止。各軸は別フィールド・別主張。

| Axis | What it is | Current carrier | What it is **not** |
| --- | --- | --- | --- |
| 1. PRICE observation / bar time | Custom Bars window / trading date of OHLCV | PRICE requestKey / bar payload | currency validity；knownAt |
| 2. All Tickers provider as-of date | Massive `date=` selector（その日に利用可能な ticker 状態） | `allTickersRequestDate` | knownAt；eligibility；bar date |
| 3. Archive attempt / fetch / ingest / eligibility | HTTP/archive lifecycle | `attemptedAt` / `fetchedAt` / `ingestedAt` / `eligibilityBoundaryAt` | provider as-of；bar time |
| 4. Evidence usable time | Forward で evidence を使い始めてよい時刻 | `evidenceEligibleAt` | historical knownAt；listing from |
| 5. Historical knownAt / PIT availability | 「その時点でユーザーが知り得た」 | **未解決**（本 chain に無し） | ingest；request `date` |

```text
allTickersRequestDate     → provider as-of state selector
eligibilityBoundaryAt     → archive OBSERVED usable boundary（All Tickers: ingestedAt）
evidenceEligibleAt        → max(price, allTickers) eligibility（Forward usable）
PRICE bar trading date    → observation economics only
knownAt                   → NOT derived from any of the above in this chain
```

---

## B. date omitted

### Claimable

| Claim | Allowed? |
| --- | --- |
| Layer B archive-level CANDIDATE（ISO PASS 等） | **YES**（既存） |
| Forward Research が `evidenceEligibleAt` 以降に candidate を参照 | **CONDITIONAL YES** |
| 「現在の currency だから全過去 PRICE bars に適用」 | **NO** |
| listing / trading currency の historical validity interval | **NO** |
| knownAt / PIT | **NO** |

### Forward-only 境界

date omitted の CANDIDATE を Forward で使う場合でも、適用開始は **高々 `evidenceEligibleAt`**。

- それより前の bar / decision / backtest window への帰属 **禁止**
- 「snapshot が現在を表す」≠「過去も同一」
- omitted date の複数取得は **別 archive evidence**；自動マージして continuous history にしない（§D）

---

## C. date supplied

### Example（固定）

```text
All Tickers request date = 2024-06-01
実際の fetch / ingest     = 2026-09-xx
```

| Statement | Verdict |
| --- | --- |
| provider as-of state ≈ 「2024-06-01 に Massive がその ticker について返した状態」の archive evidence | **YES（条件付き）** |
| 2024-06-01 時点でユーザー／戦略がその currency を知っていた（knownAt） | **NO** |
| 2024 backtest / 過去 decision へ自動投入 | **NO** |
| PRICE bars（任意時点）へ自動適用 | **NO** |
| `evidenceEligibleAt`（≈ ingest 側）以前の Forward 利用 | **NO** |

**PIT 固定規則:**  
`allTickersRequestDate`（historical）+ later ingest ⇒ **as-of raw/candidate 記録可**；**historical knownAt 主張不可**；**過去 backtest 自動投入不可**。

---

## D. 複数 evidence

Pair-wise Deriver は単一 PRICE × 単一 All Tickers。複数 All Tickers / 複数 CANDIDATE を合成する層は **未実装**。以下は Gate 規則（実装前の固定）。

| Pattern | Classification | Treatment |
| --- | --- | --- |
| same compatible as-of + USD / USD | corroboration | 重複 evidence；矛盾なし（identity は別問題） |
| same compatible as-of + USD / CAD | **CONFLICT 候補** | `CURRENCY_CONFLICT`；**latest-wins 禁止**；自動採用禁止 |
| different as-of + USD → CAD | **temporal state difference** | 単純上書き禁止；区間補間禁止；どちらも単独 CANDIDATE として保持可 |
| date omitted の複数 snapshot | distinct Forward snapshots | 連続履歴に連結しない；各々 `evidenceEligibleAt` 境界のみ |
| historical snapshot を後日取得 | as-of evidence + late eligibility | §C；knownAt にしない |
| snapshot 欠落区間 | coverage gap | 欠落区間を同一 currency と見なさない |

**compatible as-of（本 Gate 定義）:**

- 両方 `allTickersRequestDate` が **同一非 null 日付**、または
- 両方 **date omitted** かつ「同一 Forward 観測意図」と証明できない場合は **compatible とみなさない**（安全側: 別 snapshot）

same-as-of USD/CAD は reserved `TradingCurrencyEvidenceStatus.CONFLICT` の第一用途。  
different-as-of USD→CAD を CONFLICT に潰すことも latest-wins することも **禁止**。

---

## E. validity interval `[from, to)`

| Question | Gate answer |
| --- | --- |
| currency を listing validity interval へ展開できるか | **導出不可（現状）** |
| 隣接 snapshot が同じ code ならその間ずっと同一と補間してよいか | **NO** |
| date omitted CANDIDATE の `from = evidenceEligibleAt`, `to = +∞` を historical interval としてよいか | **NO**（Forward 参照境界には使えても historical validity ではない） |
| date supplied の `from = requestDate` を historical from にしてよいか | **NO**（provider as-of ≠ knownAt / listing from；欠落・遅延取得あり） |

**導出に必要なもの（不足）:**

1. Security identity 連続性（§F）  
2. 意図した as-of 密度 / 欠落区間ポリシー  
3. change detection と CONFLICT 処理  
4. historical knownAt（PIT）が要るなら別 evidence  

根拠不足のまま interval を生成しない。

---

## F. ticker reuse / identity

| Claim | Verdict |
| --- | --- |
| PRICE ticker == All Tickers ticker（case-sensitive Massive namespace） | archive join 条件として **YES**（既存） |
| 上記 ⇒ 同一 SecurityId / 連続 listing 履歴 | **NO** |
| 異なる `allTickersRequestDate` の同 ticker 行を同一 Security の時系列として連結 | **根拠不足 → 禁止** |
| ticker recycle（別発行体が後に同文字列） | **未解決；Critical** |

**Blocking dependency:**  
Historical / bar-level / validity interval / Real Backtest を進める前に **Security identity / ticker-reuse Gate** が必要。  
本 Temporal Gate だけでは identity を解決しない。

---

## G. `temporalApplicability`

### 現状維持判定

| Option | Select? | Reason |
| --- | --- | --- |
| **UNRESOLVED 維持** | **YES** | bar/historical/DailyPrice 帰属を許可する根拠が揃っていない |
| 新 enum 値を増やす | **NO（今回）** | 目的化を避ける；意味・入力・禁止用途を完全定義できない |

### もし将来状態を足す場合の最低定義テンプレ（今回は採用しない）

新状態ごとに必須:

1. 意味（何を主張するか）  
2. 入力条件（どの軸が揃っているか）  
3. 禁止用途（DailyPrice / backtest / bar 帰属など）  
4. Fail-Closed（欠落時の退行先）

候補を安易に `RESOLVED` / `FORWARD_ONLY` 等へ増やしても、§E/§F が空なら実装価値がない。  
**今回の結論: enum 拡張しない。UNRESOLVED のまま。**

---

## H. DailyPrice.currency（独立判定）

| Verdict | **NO-GO** |
| --- | --- |
| 理由 | temporalApplicability UNRESOLVED；bar-level NO-GO；identity blocking；validity interval 導出不可；PIT knownAt 未解決 |
| 本 Gate 終了で開けるか | **開けない** |

CONDITIONAL にもしない。根拠不足なら NO-GO を維持する方針に従う。

---

## I. PIT Acceptance Matrix（未来情報漏洩）

| # | Scenario | Leak risk | Required treatment |
| --- | --- | --- | --- |
| I1 | historical `date=2024-06-01` + ingest 2026-09 | 2024 decision に 2026 取得情報を混入 | candidate 記録可；**2024 backtest 投入禁止**；knownAt 主張禁止 |
| I2 | date omitted current snapshot → old PRICE bars | 現在 currency を過去 bar に backfill | **禁止** |
| I3 | later-discovered USD→CAD（different as-of）→ earlier backtest rewrite | 後知の変更で過去を書き換える | latest-wins **禁止**；過去 run 自動改ざん禁止 |
| I4 | ticker reuse（同文字列・別 identity） | 別人の currency を混線 | identity Gate まで **連続履歴禁止** |
| I5 | missing snapshots 区間を同一 currency で埋める | 無根拠補間 | **禁止**（§E） |
| I6 | same-as-of USD vs CAD | どちらを採用しても偽の確定 | **CONFLICT**；採用禁止 |
| I7 | `evidenceEligibleAt` 前の Forward 利用 | ingest 前に使えたことにする | **禁止** |
| I8 | `allTickersRequestDate` を knownAt に転用 | as-of selector を PIT に偽装 | **禁止** |

---

## Separated verdicts（必須）

| Gate | Verdict | Notes |
| --- | --- | --- |
| Archive-level currency evidence | **PASS** | Layer B CANDIDATE（PRICE + All Tickers）維持 |
| Forward applicability | **CONDITIONAL** | `evidenceEligibleAt` 以降の archive-level 参照のみ；bar なし |
| Historical / bar-level applicability | **NO-GO** | date / interval / identity 不足 |
| DailyPrice.currency | **NO-GO** | 独立判定；本 Gate で開けない |
| Real Backtest | **NO-GO** | PIT + bar-level + identity 全不足 |
| Security identity dependency | **BLOCKING** | ticker exact match ≠ Security continuity |

---

## Residual blockers

| Item | Severity |
| --- | --- |
| Security identity / ticker reuse（連続履歴の前提） | **Critical** |
| historical knownAt / CA PIT / Universe（currency PIT） | **Critical** |
| Bar-level currency attribution / past backfill | **Critical** |
| Validity interval 導出不可（無根拠補間禁止） | **Critical** |
| Multi-evidence same-as-of CONFLICT 発行層（reserved status 未配線） | **High** |
| `temporalApplicability` が UNRESOLVED のまま | **High**（意図的維持） |
| Overview optional corroboration | **High**（本 Gate 非対象） |
| MIC / Venue / FIGI | **High**（本 Gate 非対象） |

---

## Next（1 つのみ）

| Option | Select? |
| --- | --- |
| **A. Security identity / ticker-reuse Gate Review（docs only）** | **YES** |
| B. Multi-evidence same-as-of CONFLICT PoC | 後続（A の後でも可） |
| C. `temporalApplicability` enum 拡張 | **NO**（今回不採用） |
| D. DailyPrice.currency 実装 | **NO** |
| E. validity interval 実装 | **NO**（導出不可） |

**選定理由:**  
Temporal applicability を UNRESOLVED より先へ進めるには、異なる as-of の同 ticker evidence を同一 Security の連続履歴として扱えるかが **blocking**。  
interval / bar-level / Real Backtest / DailyPrice.currency は identity なしでは開けない。  
次は実装ではなく **Security identity / ticker-reuse Gate Review** のみ。

---

## Related docs

- [`trading-currency-evidence-poc.md`](trading-currency-evidence-poc.md)
- [`massive-currency-evidence-gate.md`](massive-currency-evidence-gate.md)
- [`trading-currency-evidence-gate.md`](trading-currency-evidence-gate.md)
- [`massive-all-tickers-forward-archive-poc.md`](massive-all-tickers-forward-archive-poc.md)
- [`forward-self-archive-design.md`](forward-self-archive-design.md)
- [`security-master-acceptance-criteria.md`](security-master-acceptance-criteria.md)
- [`venue-listing-identity-gate-review.md`](venue-listing-identity-gate-review.md)

---

## Merge advice

- Draft PR としてレビュー可  
- **main 自動 merge しない**  
- 本文書は DailyPrice.currency / SecurityId / Real Backtest / bar-level / latest-wins / RESOLVED 化の許可書ではない  
- Archive-level PASS ≠ Forward 無条件 GO ≠ Historical GO  
