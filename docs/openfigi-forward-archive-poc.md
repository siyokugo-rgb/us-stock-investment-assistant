# OpenFIGI Forward Self-Archive PoC

**Date (UTC):** 2026-09-15  
**Baseline SHA:** `e518a7b409794c6fdfee444becd7dccc912342e2` (origin/main verified)  
**Contract SoT:** [`forward-self-archive-design.md`](forward-self-archive-design.md)  
**Scope:** 1 domain × 1 source raw archive boundary only

| Gate | Status |
| --- | --- |
| Forward Research | **CONDITIONAL GO** (maintained) |
| Real Backtest | **NO-GO** (maintained) |

---

## Scope

| Item | Value |
| --- | --- |
| Domain | `SECURITY_MASTER` |
| Source | `openfigi.v3.mapping` |
| Endpoint | `POST https://api.openfigi.com/v3/mapping` (OpenFIGI official docs) |
| Auth | Optional header `X-OPENFIGI-APIKEY` from env `OPENFIGI_API_KEY` only |
| Purpose | Prove exact response bytes can be immutably stored with possession evidence |

### Explicitly out of scope

SecurityId generation, ticker→SecurityId, FIGI→SecurityId auto-map, Issuer selection, share-class merge, currency inference, DB, Price/Dividend/CA archives, Backtest/Strategy/Quality/QDR/Dogs, Android UI/WorkManager, provider expansion.

---

## Archive layout

```text
{archiveRoot}/
  SECURITY_MASTER/
    openfigi.v3.mapping/
      manifest.jsonl          # append-only
      raw/
        {archiveId}.raw       # immutable exact body bytes
```

Default live root: `archive-runtime/` (gitignored). Synthetic tests use temp dirs.

---

## Manifest

Append-only JSONL. Existing lines are never rewritten.

Key fields: `archiveId`, `domain`, `source`, `requestKey`, `externalIdentifier`/`Namespace`, `observedFields`, `attemptedAt`, `attemptFinishedAt`, `fetchedAt?`, `ingestedAt`, `rawPayloadHash?`, `rawPayloadUri?`, `httpStatus?`, `transportStatus`, `revisionCandidateOf?`, `relatedPriorObservationIds?`, `duplicateOf?`, `observationStatus`, `eligibilityBoundaryAt?`, `notes?`.

**No `knownAt` field is generated.**

---

## Timestamps

| Field | Meaning |
| --- | --- |
| `attemptedAt` | request attempt start (UTC Instant) |
| `attemptFinishedAt` | request attempt end |
| `fetchedAt` | set **only** when response body bytes were fully received (independent of HTTP status) |
| `ingestedAt` | raw+hash verify+manifest commit (or failure determination) time |
| `eligibilityBoundaryAt` | `ingestedAt` only for `OBSERVED`; else null |

DNS / connect / timeout / incomplete body → `fetchedAt=null`.  
HTTP 403/500 with complete body → `fetchedAt` present, status `PROVIDER_FAILURE`, eligibility null.

---

## Hash / immutability

- `rawPayloadHash` = SHA-256 of **exact provider body bytes** (lowercase hex)
- No String re-encode / pretty-print / normalize before hash
- Write path: temp → hash verify → atomic move → re-verify
- No overwrite of existing `{archiveId}.raw`
- Compression not used in this PoC (`storageObjectHash` unused)

---

## requestKey

`POST|/v3/mapping|sha256:{requestBodySha256}`

Never includes API key or secret headers.

---

## Status matrix (this PoC)

| Case | Status | fetchedAt | raw | eligibility |
| --- | --- | --- | --- | --- |
| HTTP 200 + valid mapping array | `OBSERVED` | yes | yes | = ingestedAt |
| HTTP 200 + malformed/empty/wrong shape | `REJECTED_VALIDATION` | yes | yes | null |
| HTTP 4xx/5xx + complete body | `PROVIDER_FAILURE` | yes | yes | null |
| Transport failure | `PROVIDER_FAILURE` | null | null | null |

Provider failure bodies are never decision/strategy input.

---

## Duplicate / revision

Same `(domain,source,requestKey)` + same hash → `duplicateOf` (candidate only).  
Same key + different hash → `revisionCandidateOf` / related ids.  
No authoritative replacement, no deletion, no past eligibility rewrite.

---

## Coverage

`coverageStartAt` / `coverageThroughAt` for `(SECURITY_MASTER, openfigi.v3.mapping)` derived from `OBSERVED.ingestedAt` only. Failures alongside do not claim continuous completeness (`hasNonObservedAlongside`).

---

## External identifier

Only official FIGI value from response `data[].figi` with namespace `figi`.  
Ticker-only responses leave external identifier null (never promote ticker).

---

## Auth / secrets

- Env: `OPENFIGI_API_KEY` optional
- Never committed, never logged, never in requestKey/fixtures
- Unauthenticated traffic is allowed by OpenFIGI with lower rate limits (official docs)

---

## QA / tests

Synthetic suite `OpenFigiForwardArchivePocTest` covers:

1. HTTP 200 OBSERVED + exact SHA-256  
2. Whitespace body → different hash  
3. Malformed JSON → REJECTED_VALIDATION + raw + eligibility null  
4. Empty body → REJECTED_VALIDATION  
5. HTTP 403 complete body → PROVIDER_FAILURE + fetchedAt/raw  
6. HTTP 500 complete body → PROVIDER_FAILURE + raw  
7. Transport failure → no fetchedAt/hash/uri  
8. Duplicate candidate  
9. Revision candidate without replacement  
10. Manifest/raw failure paths not OBSERVED success  
11. Hash mismatch Fail-Closed  
12. Ticker-only no SecurityId / no externalIdentifier promotion  
13. No knownAt generation  
14. Coverage OBSERVED-only  
15. Duplicate does not move past eligibility earlier  
16. Local server client smoke for requestKey/hash  

Live task: `./gradlew openFigiForwardArchivePoc` (optional; ignoreExitValue=true).

---

## Build / Android

- Collector is JVM PoC only; not Android production feature  
- Existing `:android-smoke:assembleDebug` must remain green  

---

## Residual risks

| Level | Item |
| --- | --- |
| Critical | Retrospective Security Master / Price knownAt / CA PIT / Universe / Dividend Grade still block Real Backtest |
| High | OpenFIGI alone is not a complete Security Master; forward research still needs broader archive + mapping policy |
| High | Live rate limits / keyless quotas may yield PROVIDER_FAILURE; not treated as product outage |

---

## Next step

Only after this boundary is accepted: expand carefully to additional sources/domains under the same self-archive contract — still without SecurityId auto-generation or Real Backtest GO.
