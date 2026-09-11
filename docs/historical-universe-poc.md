# Historical Universe Feasibility PoC

Phase -1 Feasibility PoC: can we reconstruct, Point-in-Time safely,

> which Securities were Universe members at `decisionAt` / `asOfDate`

without Survivorship Bias?

This document is **not**:
- proof that backtests are valid
- proof that QDR / Dogs / Quality strategies work
- a licensed data entitlement
- a production Universe implementation

Baseline `main`: `698145201ae994b69ae5c43b47915e5f7019a39f`  
Run date (UTC): 2026-09-11

## 1. PoC purpose

Validate **existence and contractability** of historical membership sources for:

| PoC key | Display name |
| --- | --- |
| `SP500_DIVIDEND_ARISTOCRATS` | S&P 500 Dividend Aristocrats |
| `DIVIDEND_KINGS` | Dividend Kings |
| `NASDAQ_DIVIDEND_ACHIEVERS` | Nasdaq Dividend Achievers (name ambiguous) |
| `SP500_QUALITY` | S&P 500 Quality → SPDJI S&P 500 Quality Index |

Contract baseline: `docs/data-contract.md` §6 (unchanged / not weakened).

## 2. Baseline principles (maintained)

1. Official index universes → **Mode A** (historical membership) preferred.
2. Without licensable PIT membership → validation **unestablished (Critical continues)**.
3. Current constituent list applied to the past → **forbidden**.
4. Dividend Kings → **Mode B** (self rebuild from consecutive dividend increases) is primary candidate; not claimed as an official index list.
5. Mode B dividend inputs → Grade A/B only; Grade C must not count years (this PoC does **not** compute streak years).

## 3. Three clocks (do not conflate)

| Clock | Meaning |
| --- | --- |
| `membershipEffectiveDate` / `membershipEffectiveAt` | When membership change takes effect |
| `knownAt` | When the change/snapshot became usable by market participants (needs primary evidence) |
| `fetchedAt` / `ingestedAt` | When **this system** obtained the row |

**Forbidden inventions:** `knownAt = effectiveDate 00:00`, announcementDate 00:00, fetchedAt, rebalance open/close, “page updated” date.

If evidence is missing → `HistoricalKnownAtStatus.UNRESOLVED_UNUSABLE`.

## 4. Official naming / providers

| Universe | Official provider | Identifier (as understood) | Naming notes |
| --- | --- | --- | --- |
| S&P 500 Dividend Aristocrats | S&P Dow Jones Indices | S&P 500 Dividend Aristocrats Index | Clear official product |
| Dividend Kings | **None** (informal screen) | n/a | Lists differ by researcher; not SPDJI/Nasdaq |
| Nasdaq Dividend Achievers | Nasdaq Global Indexes | Candidate: NASDAQ US Broad Dividend Achievers (**DAA**); family has variants | **Ambiguous** — do not silently swap variants |
| S&P 500 Quality | S&P Dow Jones Indices | S&P 500 Quality Index | Attribution sub-indices exist; do not silently swap |

## 5. Methodology sources (public probes)

| Universe | Methodology / public doc | Probe result (2026-09-11) |
| --- | --- | --- |
| Aristocrats | SPDJI Dividend Aristocrats methodology PDF (spdji.com) | HTTP **403** from this environment (doc exists publicly per SPDJI site; access blocked here) |
| Quality | SPDJI Quality Indices methodology PDF | Public methodology text available via SPDJI documentation set; live page probe HTTP **403** here |
| Nasdaq Achievers (DAA) | `indexes.nasdaqomx.com` methodology PDF for DAA | HTTP **200** PDF retrieved (`~87KB`) |
| Kings | No official methodology | Researcher pages (e.g. Sure Dividend) HTTP 200 — **secondary only** |

**Methodology available ≠ historical membership feed available.**

## 6. Current constituents

| Universe | Public current list? | Formal PIT source? |
| --- | --- | --- |
| Aristocrats | Fact sheets / ETF holdings (e.g. NOBL) show **current-ish** holdings | **No** — ETF holdings ≠ licensed index history |
| Quality | Current constituents typically via licensed SPDJI / terminal | Not free public complete history |
| Nasdaq Achievers | GIW / licensed Nasdaq index data for constituents | Public site shows index **levels** history more readily than free full constituent history |
| Kings | Multiple blog “current lists” | Not official; disagree across publishers |

Current list acquisition alone is **not PASS**.

## 7. Historical membership availability

| Universe | Historical membership (Mode A) | Classification |
| --- | --- | --- |
| Aristocrats | SPDJI licenses EOD/historical/derived; Compustat S&P constituents removed to direct SPDJI licensing (industry reporting). Free complete official history **not** confirmed | **Paid / licensed path expected** → Mode A **not established** in this PoC |
| Quality | Same SPDJI licensing pattern for constituents | **Not established** free |
| Nasdaq Achievers | Nasdaq GIW / GIDS / GIFFD provide constituents & history under **license**; redistribution restricted by Nasdaq index data policies | **Not established** without license |
| Kings | No official membership history product | Mode A **FAIL** as official index; Mode B only |

Secondary PDFs / blog archives (e.g. old NOBL annual lists, Wikipedia change tables, Sure Dividend) are **exploration only**, not formal evidence.

## 8. Snapshot vs change-event

| Universe | Likely provider shape (when licensed) | This PoC free evidence |
| --- | --- | --- |
| Aristocrats / Quality | Licensed complete snapshots and/or joiners-leavers | **Current-only / secondary** publicly |
| Nasdaq Achievers | GIW weightings / holdings files (snapshot-like) + corporate actions | Index **value** history public; full constituent history not free-confirmed |
| Kings | n/a official | Blog lists ≈ current snapshots |

Public free evidence for Mode A is closest to **C: current snapshot only** → historical backtest source **FAIL / PARTIAL**.

## 9. Effective date semantics

From public methodologies (high level):

- **Aristocrats:** annual reconstitution (January reference); quarterly reweight; effective timing described in SPDJI methodology (exact notice clocks require licensed notices).
- **Quality:** semi-annual rebalance (June/December per Quality methodology family).
- **DAA Achievers:** annual evaluation (March) with quarterly rebalance schedule per Nasdaq methodology PDF.

PoC rule: store only dates/times the source actually provides. Do not invent Instant from LocalDate.

## 10. historical knownAt evaluation

| Candidate | Verdict |
| --- | --- |
| Per-change publication Instant from free public pages | **Not established** for these universes |
| Press release / index notice timestamps | May exist case-by-case; not ingested here; not generalized |
| effectiveDate / rebalance Friday close | **Forbidden** as knownAt |
| fetchedAt | Possession only |

**Result: `HistoricalKnownAtStatus.UNRESOLVED_UNUSABLE` for free/public evidence in this PoC.**

## 11. Identifiers

| Source | Typical IDs | Stability notes |
| --- | --- | --- |
| SPDJI | Ticker + company name; permanent IDs in licensed feeds (vendor-specific) | Tickers recycle / rename |
| Nasdaq GIW | Index symbol + security identifiers in licensed files | License required |
| Blog Kings lists | Tickers only | Unsafe for history |

Vendor permanent IDs (if licensed later) are **external identifiers**, never `SecurityId` itself.

## 12. SecurityId mapping

**Forbidden:** ticker string → `SecurityId`.

Must use existing `SecurityIdentifier` (`validFrom`/`validTo` + `knownAt`) boundary.

PoC model field: `memberExternalId` + `memberIdentifierType` + optional `tickerRaw` only.  
Status: `SecurityIdMappingStatus.FORBIDDEN_FROM_TICKER_OR_PROVIDER_ID`.

## 13. Delisted members

Historical membership **must retain** members that later delist/merge.

Forbidden:

- drop tickers absent today
- filter by current listing state
- discard rows missing from current Security master

“Tradable now” ≠ “was a member then”.

## 14. History depth

| Universe | Depth claim (public) | Verified free complete constituent depth |
| --- | --- | --- |
| Aristocrats | Index launch ~2005; back-tests claimed earlier | **Not verified** as free official constituent panels |
| Quality | Base/history dates in methodology (e.g. 1990s back-history claims) | **Not verified** free constituents |
| DAA | Index inception 2003 (methodology) | Levels history public; constituent panels **license** |
| Kings | Researcher archives from ~2010 (secondary) | Not official |

## 15. License / terms (no legal conclusion)

| Provider | Free / paid | Redistribution | Commercial internal use | Status |
| --- | --- | --- | --- | --- |
| SPDJI | Historical/constituent typically **paid / direct license** | Generally restricted | Requires contract | **UNKNOWN details** without agreement; treat as **blocking** for Mode A |
| Nasdaq GIW/GIDS | Subscription / licensed | External distribution of GIW info broadly **prohibited** without order form (policy docs) | License required | **Blocking** without license |
| Blog Kings lists | Free to read | Copyright / ToS unknown; not official | Not a formal entitlement | **Not acceptable** as Mode A |

“Visible on the web” ≠ “may store and commercially reuse in an app”.

## 16. Dividend Kings — Mode A

**FAIL** as official index membership product.

No single official provider/index id. Competing lists. Secondary only.

## 17. Dividend Kings — Mode B (conditions only; no streak computation)

Mode B may proceed **later** only if inputs satisfy data-contract Grade A/B dividends and explicit self-owned rules.

Required input/algorithm contract topics (document only; **not implemented**):

| Topic | Open requirement |
| --- | --- |
| Annual total vs per-payment amount | Must define which series counts as “increase” |
| Calendar vs fiscal year | Must define year boundary |
| Split adjustment | Must define whether raw DPS or split-adjusted path is used |
| Special dividends | Include/exclude policy must be explicit |
| Skipped year / flat year | Increase vs non-decrease rule |
| Cut then recovery | Streak reset rules |
| Merger / spin-off | Successor mapping rules |
| Correction / restatement | Versioning vs latest-wins (**latest-wins forbidden**) |

**Do not call a self-built screen “the official Dividend Kings list”.**  
**Do not compute consecutive years from Alpha Vantage Grade C dividend PoC data.**

## 18. Survivorship Bias controls (fixture)

Synthetic change-log fixture encoded in unit tests:

| asOf | Members |
| --- | --- |
| 2020-06 | A |
| 2021-06 | A+B |
| 2023-01 | B |

Current snapshot `{B}` must **not** back-apply to 2020.

Implementation: `universe.poc.UniverseMembershipPocQuery` + `HistoricalUniverseFailClosedTest`.

## 19. PoC raw model

Package: `universe.poc`

- `RawUniverseMembershipObservation`
- `UniverseObservationType` = SNAPSHOT / ADD / REMOVE
- `ObservationCompleteness` = SINGLE_SNAPSHOT / COMPLETE_CHANGE_LOG / INCOMPLETE_OR_UNKNOWN
- `HistoricalKnownAtStatus`
- `UniverseSourceCatalog`
- Fail-Closed query: `UniverseMembershipPocQuery`

No production Universe aggregate. No SecurityId fields.

## 20. Fixture / QA

Network-free tests cover (among others):

1. snapshot retention  
2. ADD retention  
3. REMOVE retention  
4. ADD effective inclusive  
5. REMOVE boundary (member day-before; not on/after)  
6–7. pre/post removal membership  
8. future ADD not applied to past asOf  
9. unknown before knownAt  
10. knownAt == decisionAt allowed  
11. fetchedAt ≠ knownAt  
12. current snapshot not back-applied  
13. no SecurityId from ticker  
14. ticker recycle keeps distinct external ids  
15. delisted past member retained in past query  
16. duplicate rows not collapsed  
17. conflicting ADD/REMOVE not auto-resolved  
18. unknown effectiveDate not invented  
19. unknown knownAt not generated  
20. incomplete log ≠ perpetual membership  
21. full regression of prior suite  

## 21. Live / document evidence vs unit tests

| Track | Result |
| --- | --- |
| Unit / fixture | Fail-Closed query behavior |
| Live/doc probes | Methodology PDF/page HTTP statuses recorded above; **no** paid feed download |

HTTP 200 on a methodology PDF is **not** Mode A PASS.

## 22. Data Contract change needed?

**No weakening.** §6 remains. Optional later: add explicit vocabulary for `ObservationCompleteness` and the three clocks — not required to pass this PoC.

## 23. Remaining Critical / High

| Severity | Item |
| --- | --- |
| Critical | No free/licensed-confirmed PIT membership for Aristocrats / Quality / Achievers in this environment |
| Critical | historical knownAt unresolved for free evidence |
| Critical | current-list back-application remains a standing risk if someone bypasses contract |
| Critical | Kings Mode B blocked until Grade A/B dividend inputs exist |
| High | Nasdaq Achievers naming ambiguity (DAA vs family) |
| High | SPDJI/Nasdaq redistribution & commercial terms not fully reviewed (UNKNOWN) |
| High | Vendor permanent ID ↔ SecurityIdentifier join not designed |
| High | Delisted tracking depends on future Security master completeness |

## 24. Verdicts

| Lens | Verdict | Reason |
| --- | --- | --- |
| SE | PARTIAL | Contract + PoC boundaries sound; Mode A data entitlement missing |
| Programmer | PASS | Minimal poc models/query/tests; no overbuild |
| Data Integrity | PARTIAL | Fail-Closed query prevents several bias paths; live official history absent |
| QA | PASS | Fixture suite encodes survivorship timeline + forbidden ops |

### Per-universe

| Universe | Mode A | Mode B | Verdict |
| --- | --- | --- | --- |
| S&P 500 Dividend Aristocrats | Not established (license) | N/A (official index) | **FAIL** for free PIT; **PARTIAL** as “exists behind license (unverified entitlement)” |
| S&P 500 Quality | Not established (license) | Dangerous (need PIT fundamentals) | **FAIL** free PIT |
| Nasdaq Dividend Achievers | Not established without Nasdaq license; name ambiguous | Possible in theory; not this PoC | **FAIL** free PIT |
| Dividend Kings | **FAIL** (no official list) | Conditionally possible later with Grade A/B inputs | **PARTIAL** (Mode B design only) |

## 25. Overall

**PARTIAL / mostly FAIL for Mode A readiness.**

We can state the contract, Fail-Closed query semantics, and that **Survivorship-safe historical Universes are not operationally available** from free public sources confirmed here.

We **cannot** claim decisionAt Universes are reconstructible for backtests.

## 26. Next step?

Do **not** start Backtest / QDR / Dogs / Quality scoring / Kings year counting.

Conditional later work only:

1. Obtain/review SPDJI + Nasdaq constituent history licenses (legal/commercial), or  
2. Build Kings Mode B **after** Grade A/B dividend knownAt exists, as a **self-named** screen — never as “official Kings”.
