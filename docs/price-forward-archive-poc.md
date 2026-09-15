# PRICE Forward Self-Archive PoC (Alpha Vantage TIME_SERIES_DAILY)

**Date (UTC):** 2026-09-15  
**Baseline SHA:** `e8b532d4659f3e3889b3d81bcd52e1692d856b59` (origin/main verified)  
**Contract SoT:** [`forward-self-archive-design.md`](forward-self-archive-design.md)  
**Price feasibility SoT:** [`price-data-poc.md`](price-data-poc.md), [`price-source-entitlement-review.md`](price-source-entitlement-review.md)  
**Related archive PoC:** [`openfigi-forward-archive-poc.md`](openfigi-forward-archive-poc.md)  
**Scope:** 1 domain × 1 source raw archive boundary only

| Gate | Status |
| --- | --- |
| Forward Research | **CONDITIONAL GO** (maintained) |
| Real Backtest | **NO-GO** (maintained) |

---

## Scope

| Item | Value |
| --- | --- |
| Domain | `PRICE` |
| Source | `alphavantage.time_series_daily.raw` |
| Endpoint | `GET https://www.alphavantage.co/query` |
| Function | `TIME_SERIES_DAILY` (raw only; **not** `TIME_SERIES_DAILY_ADJUSTED`) |
| Auth | Query `apikey` from env `ALPHAVANTAGE_API_KEY` only (demo fallback for optional live smoke) |
| Purpose | Prove exact TIME_SERIES_DAILY response bytes can be immutably stored as forward possession evidence |

### Explicitly out of scope

DailyPrice mapping, SecurityId generation, symbol→SecurityId, currency inference / USD default, historical knownAt invention, adjusted series, split/dividend adjustment, total return, Backtest/Strategy/QDR/Dogs/Quality/Dividend Capture/Portfolio, Android UI, DB, scheduler, generic provider framework.

---

## Existing Price PoC verdicts (unchanged)

- Alpha Vantage TIME_SERIES_DAILY is a **raw price candidate** only
- historical knownAt = **UNRESOLVED / UNUSABLE**
- currency from TIME_SERIES_DAILY alone = **UNRESOLVED**
- provider symbol ≠ SecurityId
- DailyPrice mapping remains unimplemented
- fetchedAt / ingestedAt ≠ historical knownAt
- current fetch history must not be treated as historical as-known

This archive PoC does **not** weaken those verdicts.

---

## Archive layout

```text
{archiveRoot}/
  PRICE/
    alphavantage.time_series_daily.raw/
      manifest.jsonl          # append-only
      raw/
        {archiveId}.raw       # immutable exact body bytes
```

Default live root: `archive-runtime/` (gitignored). Synthetic tests use temp dirs.

Reuses PR #19 common primitives: `ManifestRecord`, `ManifestStore`, `ImmutableRawStore`, `Sha256Hex`, `CoverageCalculator`, status/transport enums, orphan audit.

---

## requestKey

Secret-free:

```text
GET|/query|function=TIME_SERIES_DAILY|symbol={SYMBOL}|outputsize=compact
```

- API key never enters requestKey / logs / fixtures / raw paths / manifest
- provider symbol is request identity only — **not** `externalIdentifier` / SecurityId

---

## Timestamps

| Field | Meaning |
| --- | --- |
| `attemptedAt` | request attempt start (UTC Instant) |
| `attemptFinishedAt` | request attempt end |
| `fetchedAt` | set **only** when response body bytes were fully received (independent of HTTP status) |
| `ingestedAt` | raw+hash verify+manifest commit (or failure determination) time |
| `eligibilityBoundaryAt` | `ingestedAt` only for `OBSERVED`; else null |

`eligibilityBoundaryAt` is **forward research eligibility**, not provider historical knownAt.  
Never backdated to series dates, Last Refreshed, or session close.

---

## Hash / immutability

- `rawPayloadHash` = SHA-256 of **exact provider body bytes** (lowercase hex)
- No String re-encode / pretty-print / normalize before hash
- Write path: temp → hash verify → atomic move → re-verify
- No overwrite of existing `{archiveId}.raw`

---

## Status matrix

| Case | Status | fetchedAt | raw | eligibility |
| --- | --- | --- | --- | --- |
| HTTP 200 + valid Meta Data + Time Series (Daily) | `OBSERVED` | yes | yes | = ingestedAt |
| HTTP 200 + Information / Note / Error Message | `REJECTED_VALIDATION` | yes | yes | null |
| HTTP 200 + invalid UTF-8 / malformed JSON / missing series / bad OHLCV / symbol mismatch | `REJECTED_VALIDATION` | yes | yes | null |
| HTTP 4xx/5xx + complete body | `PROVIDER_FAILURE` | yes | yes | null |
| Transport failure | `PROVIDER_FAILURE` | null | null | null |
| Raw write / path collision / manifest append failure | `LOCAL_ARCHIVE_FAILURE` | yes* | partial | null |

\* Body may have been received; eligibility stays null; never OBSERVED coverage.

HTTP 200 provider envelopes are **not** OBSERVED. Documented as `REJECTED_VALIDATION` (validation/envelope reject of a possessed body), consistent with OpenFIGI archive treatment of non-eligible HTTP-200 payloads.

---

## Validation (OBSERVED minimum)

- HTTP 2xx, body fully received, strict UTF-8 (`CodingErrorAction.REPORT`)
- valid JSON, not provider Error/Information/Note envelope
- top-level `Meta Data` and `Time Series (Daily)` present
- series object non-empty; date keys parse as `LocalDate`
- OHLCV fields present; Price PoC numeric invariants (non-negative; high≥low; open/close in range)
- requested symbol matches Meta Data symbol
- raw write + hash verify + manifest append succeed

Validation success ≠ DailyPrice creation.

---

## Currency / identity / adjusted boundary

- currency: **UNRESOLVED_FROM_TIME_SERIES_DAILY** (no USD inference; no currency manifest field)
- externalIdentifier / namespace: always null in this PoC (symbol not elevated)
- SecurityId: never generated
- knownAt: never generated
- `TIME_SERIES_DAILY` and `TIME_SERIES_DAILY_ADJUSTED` are distinct sources; adjusted not implemented

---

## Duplicate / revision

Same `(domain,source,requestKey)` + same hash → `duplicateOf` (candidate only).  
Same key + different hash → `revisionCandidateOf` / related ids.  

Different hash ≠ confirmed provider correction. No latest-wins, no deletion, no past eligibility rewrite.

---

## Coverage meaning

`coverageStartAt` / `coverageThroughAt` for `(PRICE, alphavantage.time_series_daily.raw)` = first/latest **OBSERVED.ingestedAt**.

This is **forward archive possession coverage**, not market-date span inside the payload.  
A response containing years of bars archived today still has coverageStartAt = today's ingest.

REJECTED_VALIDATION / PROVIDER_FAILURE / LOCAL_ARCHIVE_FAILURE never grant coverage.

---

## Orphan raw audit

`findOrphanRawObjects()` compares `raw/*.raw` to manifest `rawPayloadUri` references.  
Audit only — no auto-delete, no auto-promote, no eligibility invention.

---

## QA / tests

Synthetic suite `AlphaVantageDailyForwardArchivePocTest` covers:

1. HTTP 200 valid series → OBSERVED + exact SHA-256  
2. Whitespace body → hash difference  
3. Information / Note / Error Message envelopes → REJECTED_VALIDATION + raw  
4. HTTP 403/500 complete body → PROVIDER_FAILURE + raw  
5. Transport failure → no fetchedAt/hash/uri  
6. Invalid UTF-8 → REJECTED_VALIDATION + exact raw bytes  
7. Malformed JSON / missing Meta/Series / empty series / bad date / missing OHLCV  
8. Negative price / high&lt;low / symbol mismatch  
9. Duplicate + revision candidates  
10. LOCAL_ARCHIVE_FAILURE on raw write collision  
11. Orphan detection; coverage OBSERVED-only; coverage ≠ payload trading dates  
12. requestKey excludes API key; no SecurityId/knownAt/currency JSON fields  

Fixtures under `src/test/resources/archive/poc/alphavantage/` are **sanitized / synthetic**.

---

## Live smoke (optional)

```bash
./gradlew --no-daemon alphaVantageDailyForwardArchivePoc
```

Env: `ALPHAVANTAGE_API_KEY` (optional), `ARCHIVE_ROOT` (optional), `AV_SYMBOL` (optional).  
Live OBSERVED is **not** required. demo/keyless Information envelopes classifying as REJECTED_VALIDATION with raw possession is acceptable evidence of archive behavior.

### Live smoke recorded (2026-09-15, this PoC branch)

| Item | Value |
| --- | --- |
| `ALPHAVANTAGE_API_KEY` | absent |
| Symbol | IBM (demo) |
| HTTP | 200 |
| Status | `REJECTED_VALIDATION` |
| Notes | provider Information envelope; OBSERVED forbidden |
| `rawPayloadHash` | `e8ddc218d89ff77ad7e395782b4c4d28d10d328ae90c3ba597a8e7d3689b9585` |
| eligibility | null |
| coverage | observedCount=0 |
| Classification | `LIVE_PROVIDER_ENVELOPE_OR_VALIDATION_REJECTED` |
| Raw commit | **No** (`archive-runtime/` gitignored) |

---

## Build / Android

- Collector is JVM PoC only; not an Android production feature  
- `:android-smoke:assembleDebug` must remain green  

---

## Residual risks

| Level | Item |
| --- | --- |
| Critical | Historical knownAt / currency / SecurityId / CA PIT still block Real Backtest |
| High | Raw archive ≠ DailyPrice usability; adjusted series still separate |
| High | Alpha Vantage rate limits / demo envelopes frequently yield non-OBSERVED |

---

## Next steps (not this PR)

1. Security master join policy (symbol ↔ SecurityId) — separate  
2. Currency evidence source — separate  
3. Historical knownAt design — separate  
4. DailyPrice mapping only after blockers resolved  
5. Adjusted daily as distinct source if needed  

**Do not auto-promote:** Price archive PASS → DailyPrice / Backtest / currency / SecurityId / knownAt solved.
