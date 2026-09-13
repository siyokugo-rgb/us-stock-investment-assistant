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
| Android UI / Compose / production app | **Out of scope** (correct to defer) |
| Android Core Compatibility | **UNVERIFIED** (technical risk; not yet tested) |
| Broker operations (Rakuten) | Out of scope for this gate |

### Android UI vs Android Core Compatibility (do not conflate)

| Track | Meaning | Status |
| --- | --- | --- |
| **A. Android UI / Compose / production app** | Screens, navigation, Room, network UX, product shell | **Out of scope** now |
| **B. Android Core Compatibility** | Whether the current pure Kotlin core can run on Android (DEX/build, call from an app module, no JVM-only APIs) | **UNVERIFIED** — never exercised on Android |

The final product is Android, but today’s core is `kotlin("jvm")` + JDK 17 toolchain with **no** Android Gradle Plugin, Android module, or device test. That gap is a **compatibility risk**, not a reason to start UI work, and **not** a direct Real Backtest NO-GO reason.

**Not here yet:** real strategy backtests, production mapping of PoC payloads, broker execution, Android UI.

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
| Q. Android Core Compatibility | **UNVERIFIED** | `kotlin("jvm")` / JVM toolchain 17; no AGP / Android module / device test | N/A | Not proven on Android | **None** — does not stop data contracts or synthetic engine research | **Medium** | Minimal Android compatibility smoke test (no UI) |

---

## 4.1 Android Core Compatibility smoke test (future minimal check)

**Purpose:** prove the **pure core** can run on Android. This is **not** UI development and **not** product completion.

### In scope (candidates)

- An Android application module can depend on the current JVM/core artifacts  
- DEX / Android build succeeds  
- Call pure core types from Android (e.g. `SecurityId`, `IssuerId`, `DailyPrice`, `DividendEvent`, `RawFinancialFact`, `UniverseMembershipPocQuery` — names as they exist on `main`)  
- `BigDecimal` / `LocalDate` / `Instant` (and similar) behave under the chosen Android min/target conditions  
- No accidental JVM-only API dependency  
- Run **one fixed fixture** on a real device (or emulator) as a smoke execution  

### Explicitly forbidden in that smoke

- Full UI / Compose design  
- Room  
- Live API connectivity  
- Strategy screens / production app architecture  

### Device positioning

Final confirmation should include a real Android device. Gate documents must **not** hard-depend on a specific handset model. A device smoke only shows “core runs on Android”; it does **not** prove UI readiness or production readiness.

### Phase / gate impact of UNVERIFIED compatibility

| Question | Answer |
| --- | --- |
| Blocks Phase -1 full completion? | **Auxiliary risk** — yes, Phase -1 is not fully closed while core-on-Android is unproven |
| Direct reason for Real Backtest NO-GO? | **No** |
| Strategy validation blocker? | **No** |
| Required before final Android product? | **Yes** |

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

### Medium (product-path risk; not a real-backtest stopper)

| Item | Why Medium |
| --- | --- |
| **Q. Android Core Compatibility UNVERIFIED** | Final product is Android; current core is JVM/JDK 17 only. Does **not** block data contracts or synthetic engine research. Required before Android product work. |

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

1. **Gate Review finalize** (this PR / document lock).  
2. **Android Core Compatibility smoke test** — short feasibility only (module reference + DEX/build + one fixture on device/emulator). **No** Android product implementation, Compose, Room, or live APIs.  
3. **Corporate Action Feasibility PoC** (split first; Fail-Closed with raw prices).  
4. **Price knownAt / currency entitlement decision** (keep Fail-Closed; no fake mapper).  
5. If needed: **synthetic Backtest engine infrastructure** (clearly labeled non-validation).  

**Still forbidden next:** Dogs / QDR / Quality strategies, provider production, Android UI / Compose app build-out.

---

## 17. Backtest GO / NO-GO

| Question | Answer |
| --- | --- |
| Real PIT strategy Backtest GO? | **NO-GO** |
| Synthetic engine infra GO? | **Conditional YES** (must not claim validation) |
| Android Core Compatibility verified? | **NO** (UNVERIFIED; Medium) — does **not** by itself cause Real Backtest NO-GO |
| Phase -1 complete? | **NO** — feasibility incomplete (PARTIAL PoCs + unverified Android core compatibility) |
| Phase 0 proceed? | **YES** — continue locking contracts / PoCs; do not jump to strategies or Android UI |

---

## 18. Phase -1 / Phase 0 gates

| Gate | Result |
| --- | --- |
| Phase -1 complete? | **Not complete** (PARTIAL entitlements + Android Core Compatibility UNVERIFIED as auxiliary risk) |
| Phase -1 valuable? | **Yes** — safety boundaries now formal on `main` |
| Phase 0 may continue? | **Yes** (spec/contract/PoC tightening; short Android smoke allowed) |
| Phase 1 production / strategy / Android UI? | **No** |

Android Core Compatibility UNVERIFIED:

- **does** impede declaring Phase -1 fully closed (auxiliary technical risk)  
- **does not** directly justify Real Backtest NO-GO  
- **is not** a strategy-validation blocker by itself  
- **is required** before advancing to a final Android product  

---

## 19. Final judgments

| Role | Judgment |
| --- | --- |
| SE | **PARTIAL / NO-GO for real backtest.** Boundaries good; entitlements missing. Next after gate lock: Android core smoke → CA → price knownAt path. |
| Programmer | Core + PoCs appropriately minimal; **do not implement strategies or Android UI yet**. Short Android compatibility smoke is allowed; synthetic engine optional. |
| Data Integrity | Fail-Closed posture strong; PARTIAL ≠ PASS. Keep unknown≠empty and coverage windows. |
| QA | Fixture suites prove JVM boundaries, not market truth and **not** Android runtime compatibility. Prior 228 tests ≠ backtest readiness ≠ Android readiness. |

---

## 20. Explicit non-claims

This review does **not** claim:

- free PIT Mode A universes exist  
- Alpha Vantage price/dividend are strategy-eligible  
- CompanyFacts knownAt is CONFIRMED  
- Corporate Actions are handled  
- Backtests may start on real data  
- ¥10,000 Rakuten path is validated  
- current JVM core is proven Android-compatible  
- Android UI / product work should start  
