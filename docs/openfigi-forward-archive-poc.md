# OpenFIGI Forward Self-Archive PoC

**Date (UTC):** 2026-09-15  
**Baseline SHA:** `379a960e4bf65de48c1727bd294ce9f6848925d9` (origin/main; PR #21 merged)  
**Contract SoT:** [`forward-self-archive-design.md`](forward-self-archive-design.md)  
**Scope:** 1 domain × 1 source raw archive boundary + secret-free request provenance

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
      request/
        {archiveId}.request.raw   # immutable exact HTTP request body bytes (secret-free)
      raw/
        {archiveId}.raw           # immutable exact response body bytes
```

Default live root: `archive-runtime/` (gitignored). Synthetic tests use temp dirs.

Request and response objects are separate. API key / `X-OPENFIGI-APIKEY` never enter request raw, response raw, requestKey, or manifest.

---

## Manifest

Append-only JSONL. Existing lines are never rewritten.

Key fields: `archiveId`, `domain`, `source`, `requestKey`, `externalIdentifier`/`Namespace`, `observedFields`, `attemptedAt`, `attemptFinishedAt`, `fetchedAt?`, `ingestedAt`, `rawPayloadHash?`, `rawPayloadUri?`, `requestPayloadHash?`, `requestPayloadUri?`, `httpStatus?`, `transportStatus`, `revisionCandidateOf?`, `relatedPriorObservationIds?`, `duplicateOf?`, `observationStatus`, `eligibilityBoundaryAt?`, `notes?`.

**No `knownAt` field is generated.**

### Request provenance (OpenFIGI)

| Field | Meaning |
| --- | --- |
| `requestPayloadHash` | SHA-256 of **exact HTTP request body bytes** actually sent (lowercase hex). No re-serialize / pretty-print / field-sort / String normalize before hash. |
| `requestPayloadUri` | Immutable path of `request/{archiveId}.request.raw` |

Both null or both present. Required for OpenFIGI `OBSERVED` / `REJECTED_VALIDATION` / `PROVIDER_FAILURE` (after request write). Not required for Alpha Vantage / other domains.

`requestKey` body hash segment must equal `requestPayloadHash`:

`POST|/v3/mapping|sha256:{requestPayloadHash}`

### Binding provenance vs OBSERVED

- `observedIngestSucceeded` / `OBSERVED` = response path fully committed
- `bindingProvenanceReady` = secret-free request bytes immutably stored + hash aligns with `requestKey`

`OpenFigiHttpPossession.requestPayloadHash` is bound **before HTTP send** and must equal `SHA-256(requestBodyBytes)` at archive time. Mismatched possession/request bytes → Fail-Closed (`LOCAL_ARCHIVE_FAILURE`, not binding-ready, no OBSERVED). Job count is strict-parsed from request body bytes (caller-supplied count removed).

Future `ProviderSymbolBindingEvidence` may reference `mappingArchiveId` / `mappingRequestKey` / `requestPayloadHash` / `requestPayloadUri`. Symbol string-match alone remains FAIL. This PoC does **not** emit binding evidence or join Alpha Vantage symbols.

Request write failure / hash mismatch / path collision / manifest append failure → not binding-ready (`LOCAL_ARCHIVE_FAILURE` when local). Response OBSERVED without request provenance is invariant-forbidden for OpenFIGI.

OpenFIGI `ManifestRecord` invariant (construction + `fromJsonLine`): when `requestPayloadHash` is present, `requestKey` must be exactly `POST|/v3/mapping|sha256:{requestPayloadHash}`.

Audit-only: `findOrphanRequestObjects()` lists `request/*.request.raw` not referenced by any `requestPayloadUri` — never deletes, never auto-completes manifest, never promotes binding-ready/eligibility.
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

- `rawPayloadHash` = SHA-256 of **exact provider response body bytes** (lowercase hex)
- `requestPayloadHash` = SHA-256 of **exact request body bytes** sent on the wire (lowercase hex)
- No String re-encode / pretty-print / normalize before either hash
- Write path (request and response): temp → hash verify → atomic move → re-verify
- No overwrite of existing `{archiveId}.raw` or `{archiveId}.request.raw`
- Compression not used in this PoC (`storageObjectHash` unused)

---

## requestKey

`POST|/v3/mapping|sha256:{requestBodySha256}`

Never includes API key or secret headers. `{requestBodySha256}` must equal `requestPayloadHash` when request provenance is present.

---

## Status matrix (this PoC)

| Case | Status | fetchedAt | response raw | request raw | eligibility | binding-ready |
| --- | --- | --- | --- | --- | --- | --- |
| HTTP 200 + valid `data` array of objects (no error/warning) | `OBSERVED` | yes | yes | yes | = ingestedAt | yes |
| HTTP 200 + warning-only / error / empty or non-array `data` / non-object row / warning+data | `REJECTED_VALIDATION` | yes | yes | yes | null | yes |
| HTTP 4xx/5xx + complete body | `PROVIDER_FAILURE` | yes | yes | yes | null | yes |
| Transport failure | `PROVIDER_FAILURE` | null | null | yes | null | yes |
| Request raw write / request hash / request path collision | `LOCAL_ARCHIVE_FAILURE` | maybe | no | no | null | **no** |
| Response raw write / hash verify / path collision / manifest commit failure | `LOCAL_ARCHIVE_FAILURE` | yes* | partial | yes† | null | † yes if request committed; **no** if manifest failed |

\* Body may have been received; eligibility stays null; never counts as OBSERVED coverage.  
† Request object may already be on disk when response/manifest fails; binding-ready requires committed request fields on the returned record and successful manifest append.

HTTP 200 alone never promotes provider-level mapping failure (`warning` / `error` / absent-or-invalid `data`) to `OBSERVED`.

Provider failure and local archive failure bodies are never decision/strategy input.

---

## Duplicate / revision

Same `(domain,source,requestKey)` + same hash → `duplicateOf` (candidate only).  
Same key + different hash → `revisionCandidateOf` / related ids.  
No authoritative replacement, no deletion, no past eligibility rewrite.

---

## Coverage

`coverageStartAt` / `coverageThroughAt` for `(SECURITY_MASTER, openfigi.v3.mapping)` derived from semantic `OBSERVED.ingestedAt` only.  
`REJECTED_VALIDATION` / `PROVIDER_FAILURE` / `LOCAL_ARCHIVE_FAILURE` / `MISSING` never grant coverage.  
Failures alongside do not claim continuous completeness (`hasNonObservedAlongside`).

Manifest rows also enforce status invariants at construction / `fromJsonLine` (Fail-Closed).

---

## External identifier

`externalIdentifier` / namespace=`figi` only when the **whole manifest record** is uniquely determined:

- request job count = 1
- response item count = 1
- valid `data` candidate count = 1
- non-blank `figi` count = 1

Otherwise `externalIdentifier` stays null. Raw payload still keeps all candidates.  
No first-FIGI auto-selection. No SecurityId generation. Ticker is never promoted.
---

## Auth / secrets

- Env: `OPENFIGI_API_KEY` optional
- Never committed, never logged, never in requestKey / request raw / response raw / fixtures
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
7. Transport failure → no fetchedAt/hash/uri (request provenance still stored)  
8. Duplicate candidate  
9. Revision candidate without replacement  
10. Manifest/raw failure → `LOCAL_ARCHIVE_FAILURE` (not OBSERVED / not PROVIDER_FAILURE)  
11. Hash mismatch Fail-Closed  
12. Warning-only / error-only → not OBSERVED  
13. `data` string/object/empty/non-object row → REJECTED_VALIDATION  
14. 1 job / 1 candidate → figi externalIdentifier; multi-candidate / multi-job → null  
15. No knownAt / no SecurityId generation  
16. Coverage OBSERVED-only; REJECTED / LOCAL_ARCHIVE_FAILURE excluded  
17. Manifest status invariants + `fromJsonLine` Fail-Closed  
18. Duplicate does not move past eligibility earlier  
19. Local server client smoke for requestKey/hash  
20. Exact sent request bytes stored under `request/{id}.request.raw`  
21. `requestPayloadHash` == SHA-256(exact request bytes); whitespace changes hash  
22. `requestKey` body hash == `requestPayloadHash`  
23. Request raw overwrite forbidden → not binding-ready  
24. Request write failure → not binding-ready (even if response would succeed)  
25. Response collision with request saved → not OBSERVED; request binding-ready only  
26. API key / header secrets absent from request raw + manifest  
27. `idType` / `idValue` / `exchCode` recoverable from request raw  
28. OpenFIGI OBSERVED without request provenance rejected by invariant  
29. Alpha Vantage OBSERVED does not require request payload fields  

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
| High | Request provenance enables future ProviderSymbolBindingEvidence references but does **not** establish Alpha Vantage ↔ FIGI join |

---

## Next step

ProviderSymbolBindingEvidence design/implementation that **references** mapping request/response provenance — still without SecurityId auto-generation, DailyPrice mapping, Join Candidate promotion, or Real Backtest GO.
