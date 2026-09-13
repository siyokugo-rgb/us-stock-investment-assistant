# Feasibility / Phase 0 Gate Review

**Baseline (GitHub `main`):** `3b55acb554f7456a2eeb1a283d56e03c0c09d9ef`  
**Review type:** Document / SE audit only (no production code changes)  
**Source of Truth:** Current `main` code + docs (not older chat / outdated Critical lists)

---

## 1. Baseline

| Item | Value |
| --- | --- |
| Repository | `siyokugo-rgb/us-stock-investment-assistant` |
| Branch audited | `main` @ `3b55acb554f7456a2eeb1a283d56e03c0c09d9ef` |
| Last merge | PR #11 Historical Universe Feasibility PoC |
| Overall PoC posture | **PARTIAL** (boundaries established; live PIT entitlements incomplete) |
| Tests / build (this review) | Not re-run (docs-only) |

Merged feasibility artifacts on `main`:

| Artifact | Location | Formal status |
| --- | --- | --- |
| PIT-safe core | `pit/`, domain models | Established (unit-tested) |
| Data Contract | `docs/data-contract.md` | Established (not weakened) |
| SEC submissions PoC | `sec/poc` + `docs/sec-edgar-poc.md` | **PARTIAL** |
| SEC accession PoC | `sec/poc` + `docs/sec-edgar-accession-poc.md` | **PARTIAL** |
| CompanyFacts PoC | `sec/poc` + `docs/sec-companyfacts-poc.md` | **PARTIAL** |
| Issuer / Security boundary | `issuer/`, `security/` | **RESOLVED** in core |
| Fact normalization / version boundary | `fundamentals/` | **RESOLVED** (no latest-wins) |
| Price Feasibility PoC | `market/poc` + `docs/price-data-poc.md` | **PARTIAL** |
| Dividend Feasibility PoC | `dividend/poc` + `docs/dividend-data-poc.md` | **PARTIAL** (Grade C) |
| Historical Universe PoC | `universe/poc` + `docs/historical-universe-poc.md` | **PARTIAL** / Mode A mostly **FAIL** free |

---

## 2. Current development stage

**Primary stage:** Phase -1 feasibility verification + Phase 0 data-contract / domain-boundary lock.

**Secondary stages present:**

| Stage | Status |
| --- | --- |
| Technical feasibility | In progress (multiple PARTIAL PoCs) |
| Data contract | Established |
| Domain model (core) | Established for ID / PIT / raw facts / price-dividend shapes |
| Production providers | Not started |
| Backtest infrastructure | Not started |
| Strategy validation | Not started |
| Android / operations | Out of scope |

**Not here yet:** real strategy backtests, production mapping of PoC payloads, broker execution.

---

## 3. Merged deliverables (resolved vs PARTIAL)

### Resolved on `main` (do not keep as Critical)

| Item | Evidence on `main` |
| --- | --- |
| IssuerId / SecurityId separation | `issuer/IssuerId.kt`, `security/SecurityId.kt` |
| CIK not on Security | `IssuerIdentifierType.CIK`; Security `IdentifierType` has no CIK |
| FundamentalSnapshot is issuer-scoped | `fundamentals/FundamentalSnapshot.kt` uses `issuerId` |
| CompanyFacts raw vs normalized boundary | `RawFinancialFact` + normalizer / `NormalizedFinancialConcept` |
| latest-wins forbidden | `FinancialFactCandidateSet` KDoc + API (`candidatesFor` only) |
| amendment auto-replace forbidden | `/A` → classification only; accession store refuses overwrite |
| ticker → SecurityId forbidden | Universe/Price/Dividend PoCs: `FORBIDDEN_FROM_TICKER_OR_PROVIDER_ID` |
| current Universe back-apply forbidden | `UniverseMembershipPocQuery` + Fail-Closed tests |
| incomplete change-log Fail-Closed | completeness + coverage window checks |
| unknown universe ≠ empty universe | zero known / outside coverage → not confirmed empty `MEMBERS` |
| synthetic/mock live fallback forbidden | PoC clients Fail-Closed; no demo→success substitution |
| Data Contract §6 Universe rules | maintained; not weakened by PoCs |

### PARTIAL (formal PoC results — not “done”)

| Domain | Verdict | Meaning |
| --- | --- | --- |
| SEC submissions / accession / CompanyFacts | PARTIAL | Fetch + Fail-Closed boundaries OK; CONFIRMED historical knownAt not established |
| Price (Alpha Vantage) | PARTIAL | Mapping blocked: knownAt, currency, SecurityId, live key/history |
| Dividend (Alpha Vantage) | PARTIAL / Grade C | Not advance-knowledge input; knownAt/currency/type unresolved |
| Historical Universe | PARTIAL / Mode A mostly FAIL free | Safety boundaries locked; free PIT membership not established |

---

## 4. Gate Matrix

| Domain | Formal status | Evidence on `main` | PIT status | Production mapping | Backtest impact | Severity | Next minimal action |
| --- | --- | --- | --- | --- | --- | --- | --- |
| A. Security / Identifier | RESOLVED (core) | `security/*` | ID history supports PIT windows | Ready as model | Enables ID discipline | — | Keep Fail-Closed multi-hit rules |
| B. Issuer / CIK | RESOLVED (core) | `issuer/*` | Relation knownAt required by contract | Ready as model | Enables fundamentals join path | — | Operational conflict playbook later |
| C. SEC filing metadata | PARTIAL | `sec/poc`, sec-edgar docs | acceptanceDateTime = lower-bound only | Not production | Blocks fundamental PIT decisions | High for fund strategies | Keep as evidence store; no knownAt promotion |
| D. CompanyFacts | PARTIAL | companyfacts PoC + fundamentals boundary | filed ≠ knownAt | Not production | Blocks Quality / fund strategies | High for those strategies | Join accession; still not CONFIRMED knownAt |
| E. Fundamental raw facts | RESOLVED boundary / PARTIAL data | `fundamentals/*` | Version candidates only | Numbers not strategy-ready | Blocks Quality metrics | High for Quality | No final value resolver yet (correct) |
| F. Price | PARTIAL | price PoC + `DailyPrice` | historical knownAt unresolved | Mapping blocked | **Blocks all return-based backtests** | **Critical** | Decide licensed/source path; do not invent knownAt/currency |
| G. Dividend | PARTIAL Grade C | dividend PoC + `DividendEvent` | Grade C only | Mapping blocked | Blocks QDR / capture / Kings Mode B; Dogs yield only if post-hoc allowed | Critical for div strategies; High otherwise | Grade A/B source or accept post-hoc-only |
| H. Historical Universe | PARTIAL | universe PoC + docs | free Mode A unestablished | No live feed | Blocks universe strategies; not all single-name slices | Critical for Dogs/QDR/Quality universes | License Mode A **or** define non-universe slice |
| I. Corporate Action | DESIGN ONLY | Data Contract §5; no Kotlin CA types | N/A | Absent | **Blocks correct multi-day returns on raw prices** | **Critical** for any multi-event return path | CA PoC / contract enforcement before multi-name TR |
| J. Currency | PARTIAL / unresolved | required on models; PoCs unresolved | N/A | Blocked | Blocks USD/JPY and multi-ccy | Critical for JP capital accounting; High for USD-only research | Explicit currency evidence join |
| K. Trading calendar / session | ABSENT | no calendar engine | N/A | Absent | High for session/stale semantics | High | Define later with price source |
| L. Security master / delisted history | PARTIAL | ID validity windows only | Incomplete | Incomplete | Survivorship risk if master incomplete | High | Expand with CA + universe feeds |
| M. Quality metrics | ABSENT | catalog name only | N/A | Absent | Blocks Quality strategies | High for those strategies | Do not invent formulas early |
| N. ¥10,000 execution constraints | SPEC ONLY | README / phase-0 | N/A | Absent | Not required for engine research | Ops Critical later | Defer to pre-production |
| O. Rakuten Securities | SPEC ONLY | manual broker assumption | N/A | Absent | Ops only | Ops High later | Defer |
| P. Backtest engine | ABSENT | README explicit | N/A | Absent | No engine yet | Infra High | May start **synthetic** engine only |

---

## 5. Resolved (exclude from old Critical lists)

1. IssuerId / SecurityId separation  
2. Security-side CIK removal  
3. FundamentalSnapshot issuerId-only  
4. CompanyFacts raw fact / normalized concept separation  
5. latest-wins prohibition  
6. amendment auto-replace prohibition  
7. ticker → SecurityId prohibition  
8. current Universe historical back-apply prohibition  
9. incomplete change-log Fail-Closed + explicit coverageStart/Through  
10. unknown ≠ empty universe  
11. synthetic/mock fallback prohibition for live paths  
12. Data Contract existence (phase-0 “contract未設計” is obsolete)

---

## 6. Critical blockers (redefined)

Only items that truly stop **real-data strategy validation** or make returns false.

| Blocker | Stops all backtests? | Stops some strategies? | Ops only? | Notes |
| --- | --- | --- | --- | --- |
| Historical price knownAt + currency + SecurityId mapping | **Yes** for PIT return validation | — | — | Price PoC PARTIAL; cannot map to `DailyPrice` safely |
| Corporate Action (split etc.) for raw-price total return | **Yes** for multi-period TR / size continuity | Single-day OHLC research less affected | — | Contract exists; **no implementation** |
| Historical Universe Mode A entitlement | No | **Yes** — Dogs / QDR / Quality universes | — | Free Mode A unestablished (PR #11 formal result) |
| Dividend Grade A/B knownAt | No | **Yes** — QDR, Dividend Capture, Kings Mode B, PIT yield decisions | — | Grade C ≠ decision input |
| Fundamental historical knownAt + unit/version resolver | No | **Yes** — Quality / fundamental screens | — | Candidate set only |
| Security master completeness / delistings | No | **Yes** — any broad historical universe | — | Tied to CA + membership |
| FX / ¥10k / Rakuten fees / fractional | No | No for USD research engine | **Yes** | Pre-production |

**Not automatically Critical anymore:** “Issuer model missing”, “Data Contract missing”, “HTTP absent” (PoCs exist), “tests failing”.

---

## 7. High (important, not universal stop)

| Item | Why High |
| --- | --- |
| Trading calendar / session / stale thresholds | Affects knownAt/session semantics |
| Price full-history / premium entitlement | Live readiness |
| Dividend type / revision semantics | Capture & yield quality |
| Nasdaq Achievers naming ambiguity | Universe identity risk |
| Vendor permanent ID ↔ SecurityId join design | Mapping ops |
| Quality metric definition | Strategy later |
| Engine infrastructure absence | Can start synthetic, not real validation |

---

## 8. Strategy dependency matrix (no strategy evaluation)

| Strategy | Needs | Current blocks |
| --- | --- | --- |
| Classic Dogs of the Dow | Price TR + historical Dow universe + dividend yield + CA | Universe Mode A; price knownAt/currency; CA; yield PIT if used as decision |
| Quality-Gated Dogs | Dogs + Quality fundamentals PIT | All Dogs blocks + fundamentals knownAt + Quality definition |
| QDR | Price + dividend PIT Grade A/B + universe + CA | Dividend Grade C; universe; price; CA |
| Quality Mean Reversion | Price + fundamentals PIT + universe/universe-alternative | Fundamentals knownAt; Quality; price; CA |
| Dividend Capture | Dividend PIT Grade A/B + calendar/session + price | Dividend Grade C; calendar |
| Market Benchmark | Price TR + CA (+ optional listed universe) | Price knownAt/currency; CA for correct TR |

---

## 9. Minimum Backtestable Slice

**Question:** Is there a smallest real-data backtest that respects current contracts?

| Candidate | Feasible now? | Why |
| --- | --- | --- |
| Market benchmark (index TR) | **No** | No PIT-safe price series mapping; CA absent |
| Single-security buy-and-hold on raw AV prices | **No** as PIT decision backtest | knownAt/currency/SecurityId unresolved; split-ignore returns unsafe |
| Price-only research ignoring PIT | Contract-violating | Forbidden by project rules |
| Synthetic engine unit simulation | **Yes as infrastructure** | Not real validation |

### Verdict: **C. Currently impossible** for real-data strategy / PIT backtest.

Closest honest path later: licensed/proven price + CA handling, then either (a) single-name post-hoc analysis with explicit non-PIT label, or (b) licensed universe + PIT inputs. Neither is available on `main` today.

---

## 10. Backtest engine vs real validation

| Track | May start now? | Meaning |
| --- | --- | --- |
| **Engine infrastructure** (clock, orders, bookkeeping, **synthetic fixtures**) | **YES (allowed)** | Does not claim market truth |
| **Real strategy validation** (historical PIT data, CA, survivorship, FX/fees) | **NO** | Blocked by Critical data gaps |

Do **not** treat engine scaffolding as Backtest GO.

---

## 11. Corporate Action priority

| Check | Result |
| --- | --- |
| CA data contract | Design-only (`data-contract.md` §5) |
| Kotlin CA models / processors | **Absent** |
| Identifier history alone | **Not** CA resolution |
| Raw price without split handling | Multi-day returns / share continuity **unsafe** |

**SE priority:** Corporate Action PoC is a **top-tier next feasibility workstream**, in parallel priority with price knownAt/currency source decisions. For any total-return path, CA is Critical—not optional polish.

---

## 12. Price source next step (no new provider work this review)

Alpha Vantage Price PoC remains **PARTIAL**.

Priority order (SE):

1. **Decide whether AV can ever supply historical knownAt + currency evidence** (likely no for free daily).  
2. **Corporate Action contract/PoC** so raw series are usable for TR once a source exists.  
3. **Licensed / alternative source evaluation** (research later—not this PR).  

Do not build DailyPrice mappers that invent knownAt/USD.

---

## 13. Dividend next step

| Use | Allowed now? |
| --- | --- |
| QDR / Dividend Capture decision input | **BLOCKED** (Grade C) |
| Dogs yield as **ex-post** analysis with explicit non-PIT label | Possible only as research caveat; **not** decisionAt input |
| Annual streak rebuild (Kings Mode B) | **BLOCKED** until Grade A/B |
| Past decision (“I knew the dividend”) | **No** |

---

## 14. Historical Universe next step (PR #11 formal)

| Claim | Status |
| --- | --- |
| free Mode A | **Not established** |
| licensed source | **Not contracted** |
| Kings Mode B | Waits dividend Grade A/B |
| current list back-apply | **Forbidden** (enforced in PoC) |
| coverageStart/Through | **Required**; outside → INDETERMINATE |

Universe contract is **mandatory** before Dogs/QDR/Quality-universe backtests.  
It is **not** required for a future non-universe single-name slice—but that slice is still blocked by price/CA Criticals.

---

## 15. ¥10,000 / Rakuten Securities

| Topic | Backtest research need? | Pre-production need? |
| --- | --- | --- |
| ¥10,000 capital | Optional for research sizing | **Yes** |
| US stock min order / fractional | No for engine math | **Yes** |
| Fees | Optional stress later | **Yes** |
| USD/JPY | Only if JPY accounting | **Yes** |
| Yen vs FX settlement | No | **Yes** |
| Tax / dividend withholding | No for first engine | **Yes** |
| Insufficient funds handling | No | **Yes** |

Treat as **operations gate**, not current Critical for engine scaffolding.

---

## 16. Next minimal work (ordered)

1. **Document status hygiene** (this review) — this PR.  
2. **Corporate Action feasibility PoC** (split first; Fail-Closed with raw prices).  
3. **Price knownAt/currency entitlement decision** (keep Fail-Closed; no fake mapper).  
4. Optionally: **synthetic Backtest engine scaffolding** (clearly labeled non-validation).  
5. **Do not** start Dogs/QDR/Quality/Android/provider production.

---

## 17. Backtest GO / NO-GO

| Question | Answer |
| --- | --- |
| Real PIT strategy Backtest GO? | **NO-GO** |
| Synthetic engine infra GO? | **Conditional YES** (must not claim validation) |
| Phase -1 complete? | **NO** — feasibility incomplete (PARTIAL PoCs remain) |
| Phase 0 proceed? | **YES** — continue locking contracts / PoCs; do not jump to strategies |

---

## 18. Phase -1 / Phase 0 gates

| Gate | Result |
| --- | --- |
| Phase -1 complete? | **Not complete** (expected: many PARTIAL/FAIL free entitlements) |
| Phase -1 valuable? | **Yes** — safety boundaries now formal on `main` |
| Phase 0 may continue? | **Yes** (spec/contract/PoC tightening) |
| Phase 1 production / strategy? | **No** |

---

## 19. Final judgments

| Role | Judgment |
| --- | --- |
| SE | **PARTIAL / NO-GO for real backtest.** Boundaries good; entitlements missing. Next: CA + price knownAt path. |
| Programmer | Core + PoCs appropriately minimal; **do not implement strategies yet**. Synthetic engine optional. |
| Data Integrity | Fail-Closed posture strong; PARTIAL ≠ PASS. Keep unknown≠empty and coverage windows. |
| QA | Fixture suites prove boundaries, not market truth. Prior 228 tests ≠ backtest readiness. |

---

## 20. Explicit non-claims

This review does **not** claim:

- free PIT Mode A universes exist  
- Alpha Vantage price/dividend are strategy-eligible  
- CompanyFacts knownAt is CONFIRMED  
- Corporate Actions are handled  
- Backtests may start on real data  
- ¥10,000 Rakuten path is validated  
