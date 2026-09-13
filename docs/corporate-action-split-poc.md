# Corporate Action Split Feasibility PoC

**Baseline (GitHub `main` HEAD at branch cut):** `f51ec16265d210fd359a0e87952e13c10a0bd5e1`

**Scope:** stock split / reverse split **only**.  
**Not in scope:** merger / acquisition / spin-off / cash merger / ticker change / delisting engines, portfolio ledger, tax, cash-in-lieu, adjusted price generation, strategy, Android UI, broker, DB, ML.

This PoC does **not** claim that PIT-safe backtests are unlocked.

---

## 1. Baseline / existing contract audit

Audited:

- `docs/data-contract.md` §5 Corporate Action Contract
- `docs/feasibility-gate-review.md` (CA = Critical / design-only)
- `docs/price-data-poc.md` (raw OHLC; adjusted ≠ execution)
- `SecurityId`, `SecurityIdentifier`, `DailyPrice`, `Pit`
- Existing JVM suite (229 tests before this PoC)

Formal policy retained (not weakened):

| Rule | Status |
| --- | --- |
| stock split / reverse → **SecurityId continues** | retained |
| raw price may jump across split | retained |
| share quantity must change **with** split ratio | retained |
| price-only split-adjust with quantity left unchanged | **forbidden** |
| effective date and knownAt separated | retained |

Source research companion: [`stock-split-source-research.md`](stock-split-source-research.md).

---

## 2. Scope of this PoC

**In:**

- Source feasibility for split / reverse split
- Explicit ratio semantics (`oldShares` / `newShares`)
- Date field separation
- historical knownAt evaluation (event knowledge vs accounting application)
- SecurityId boundary (no symbol→id invention)
- raw price boundary
- quantity transform math helper only
- Fail-Closed QA fixtures
- Documented public-event examples (not universe proof)

**Out:** full CA engine, portfolio, cash-in-lieu, tax lots, adjusted series, live paid feeds as production entitlement.

---

## 3. Provider / source candidates

See full table in [`stock-split-source-research.md`](stock-split-source-research.md).

Condensed:

| Source | Free? | Structured ratio | Publish/acceptance timestamp | PIT `knownAt` for backtests | Notes |
| --- | --- | --- | --- | --- | --- |
| SEC EDGAR 8-K / EX-99 | Yes | Narrative | acceptance/filed | **Partial** (disclosure clock candidate; NLP gaps) | Formal public |
| Nasdaq Corporate Action Alerts (HTML) | View free | Text | page publish + effective | Partial if self-archived | Not a historical API |
| Nasdaq Daily List | Paid | Yes | product fields | Better under license | CUSIP licensing |
| NYSE MEF | Paid | Yes | effective + publish; revisions | Yes in subscription window | Limited public lookback |
| FINRA OTC Daily List | Web free / API gated | Yes (OTC) | Daily List day | Partial; **OTC only** | Not listed universe |
| Issuer IR / press | Free to read | Prose | PR / 8-K mirror | Per-event partial | Fragmented |
| Polygon / Tiingo / Alpha Vantage free split APIs | Free tiers | Often ex/effective + ratio | Usually **no** entitlement Instant | **No** as PIT knowledge feed | Adjustment feeds ≠ knownAt |
| LSEG / Bloomberg / CRSP | Paid | Yes | Announcement + ex + effective (vendor-dependent) | Best commercial path | License required |

### Verdict on free formal PIT-safe source

**No free, formal, structured, PIT-safe historical US stock-split feed suitable as a ready-made backtest entitlement was found.**

Unofficial scraping is **not** promoted to a formal source.

---

## 4. Live / document confirmation targets

This Cloud Agent run did **not** exercise paid entitlements or login-gated feeds.

Document-level public examples (illustrative; **not** universe proof; **not** CONFIRMED knownAt):

| Example | Type | Public narrative | Ratio semantics used in PoC | knownAt |
| --- | --- | --- | --- | --- |
| Apple 4-for-1 (2020) | forward | Widely documented issuer/SEC materials | oldShares=1, newShares=4 | **UNRESOLVED** in this PoC (no Instant evidence ingested) |
| Typical 1-for-10 reverse (issuer-dependent) | reverse | Exchange alerts / 8-K narrative | oldShares=10, newShares=1 | **UNRESOLVED** unless Instant evidence captured |

One documented success ≠ all-market feasibility.

If a source requires paid contract / login: record **PARTIAL/FAIL** for that path rather than workaround scraping.

---

## 5. Ratio semantics (fixed)

```text
2-for-1 forward:  oldShares = 1, newShares = 2
3-for-2 forward:  oldShares = 2, newShares = 3
1-for-10 reverse: oldShares = 10, newShares = 1
```

Model: `corporateaction.poc.SplitRatio(oldShares: BigDecimal, newShares: BigDecimal)`.

Forbidden:

- guessing orientation of `"2:1"`
- unifying provider-specific strings without confirmed semantics (`AmbiguousSplitRatioParser` Fail-Closes)
- Double
- zero / negative shares

`STOCK_SPLIT` requires `newShares >= oldShares`; `REVERSE_SPLIT` requires `newShares < oldShares`.

---

## 6. Date semantics

Separated fields on `RawSplitObservation`:

| Field | Meaning |
| --- | --- |
| `announcedDate` / `announcedAt` | announcement calendar date / Instant if evidenced |
| `effectiveDate` | corporate-action effective date |
| `exDate` | ex date when provided |
| `recordDate` | record date when provided |
| `fetchedAt` | our retrieval Instant |

Forbidden promotions:

- `effectiveDate` → knownAt
- `exDate` → knownAt
- announcement `LocalDate` at 00:00 → knownAt
- `fetchedAt` → historical knownAt

---

## 7. knownAt evaluation

### A. Event knowledge

“Did the decision process know the split **before** trading on it?”  
Requires Instant evidence → `HistoricalKnownAtStatus.RESOLVED_WITH_EVIDENCE`.  
Otherwise **UNRESOLVED** (default for free feeds examined).

### B. Accounting application

After effective date passes, applying quantity transform is a **separate** duty from A.  
Still requires:

- explicit ratio
- known `effectiveDate` (`requireEffectiveDateForAccounting()`)
- Security mapping outside this raw model
- no conflicting unresolved revisions

Using later-corrected current CA history to strengthen past **pre-trade** decisions is forbidden.

---

## 8. SecurityId boundary

- Normal split / reverse: **same SecurityId continues** (contract §5).
- `providerSymbol` is external only.
- `RawSplitObservation` **does not** carry `SecurityId`.
- Do not mint SecurityId from ticker / provider symbol.
- Do not bind historical events by current ticker string alone (recycle / rename).

---

## 9. Raw price boundary

- Raw OHLCV discontinuities across splits are **not** auto-corrected as data errors.
- Forbidden: adjust raw prices only; leave quantity unchanged; use adjusted close as execution; rewrite historical raw OHLC from ratio; infer splits from price jumps without an event.
- Adjusted time-series generation is **out of scope**.

---

## 10. Quantity adjustment boundary

Helper only: `SplitQuantityMath.applySplitQuantity`.

```text
newQuantity = oldQuantity × newShares / oldShares
```

- Exact `BigDecimal` / `MathContext.UNLIMITED`
- Non-terminating expansion → Fail-Closed (no silent round)
- No portfolio engine, cash-in-lieu, tax lot, broker rounding, trade generation

---

## 11. Reverse split

Covered in model + tests (e.g. 1-for-10):

- ratio direction enforced
- quantity decreases
- raw price may gap up (not auto-fixed)
- fractional shares possible mathematically
- cash-in-lieu **not** implemented

---

## 12. Duplicate / revision / correction

`SplitObservationConflicts`:

- conflicting ratios for same provider/symbol/eventId → report, **no auto-resolve**
- conflicting effective dates → report, **no auto-resolve**
- identical evidence duplicates → detectable; **not** “latest fetchedAt wins”
- missing version / updated timestamp → **latest-wins forbidden**; keep candidates or UNRESOLVED
- do not fabricate past versions from current-only responses

---

## 13. License

| Class | PoC stance |
| --- | --- |
| SEC EDGAR public | Free access/reuse under SEC public dissemination policy; rate limits |
| Exchange HTML alerts | Viewable; structured Daily List / MEF are licensed products |
| Free vendor split APIs | Typically personal/non-commercial or no redistrib; **not** PIT knownAt feeds |
| Paid LSEG/Bloomberg/exchange | Require commercial contracts for production |

Redistribution of vendor CA databases is **not** assumed.

---

## 14. PIT eligibility

| Path | Eligibility |
| --- | --- |
| Knowledge-PIT backtest using free split APIs’ ex/effective dates as knownAt | **FAIL / ineligible** |
| Accounting application after effective with CONFIRMED event fields | Conditionally possible **after** Security mapping + conflict resolution |
| SEC/exchange Instant disclosure clocks | **PARTIAL** research path only |

---

## 15. Backtest eligibility

**Not cleared.**  
Backtest blocker for multi-day raw-price total return / share continuity **remains Critical** until a licensed or reconstructable PIT-safe CA source with knownAt evidence exists.

---

## 16. Data Contract change

§5 wording **not weakened**.

Minimal additive note only (see §5.1 in `data-contract.md`): points to this PoC package and restates Fail-Closed knownAt / no latest-wins for conflicting split observations.

---

## 17. Remaining Critical / High

| Severity | Item |
| --- | --- |
| **Critical** | No free formal PIT-safe historical split feed for backtests |
| **Critical** | Multi-name / multi-day TR on raw prices still unsafe without CA entitlement |
| **High** | SecurityId↔provider symbol historical join still unresolved |
| **High** | Correction/version streams absent on free APIs |
| **Medium** | NLP extraction from 8-K not implemented (and not a complete feed) |
| **Medium** | Android device runtime still UNVERIFIED (orthogonal; smoke module unchanged) |

---

## 18. PASS / PARTIAL / FAIL

| Lens | Result |
| --- | --- |
| SE | **PARTIAL** — boundaries clarified; entitlement missing |
| Programmer | **PASS** for scoped PoC helpers/tests; no engine |
| Data Integrity | **PARTIAL** — Fail-Closed math/conflicts; no production CA store |
| QA | **PASS** for fixture suite; live paid paths not claimed |
| **Overall** | **PARTIAL** |

---

## PoC code map

```
src/main/kotlin/corporateaction/poc/
  SplitRatio.kt
  SplitActionType.kt
  HistoricalKnownAtStatus.kt
  SplitEvidenceStatus.kt
  RawSplitObservation.kt
  SplitQuantityMath.kt
  SplitObservationConflicts.kt
  AmbiguousSplitRatioParser.kt

src/test/kotlin/corporateaction/poc/
  CorporateActionSplitPocTest.kt
```

Android module `:android-smoke` was **not** structurally changed by this PoC.
