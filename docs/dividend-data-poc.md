# US Equity Dividend Feasibility PoC

Phase -1 Feasibility PoC for US equity **dividend event** acquisition via Alpha Vantage `DIVIDENDS`.

This document is **not**:
- proof of QDR / Dividend Capture validity
- proof that historical `knownAt` is solved
- proof that `DividendEvent.currency` / `dividendType` are solved from this endpoint alone
- proof that backtests are valid

Baseline `main`: `dc68655612e3ca99a9748d2305d39b05d733f199`  
Run date (UTC): 2026-09-11

## 1. Provider / endpoint

| Item | Value |
| --- | --- |
| Provider | Alpha Vantage |
| Official docs | https://www.alphavantage.co/documentation/ (`Corporate Action - Dividends`) |
| Function | `DIVIDENDS` |
| Endpoint | `GET https://www.alphavantage.co/query` |
| Auth | query param `apikey` (never committed) |
| Env var | `ALPHAVANTAGE_API_KEY` |
| Official description | returns **historical and future (declared)** dividend distributions |

Example (official): `https://www.alphavantage.co/query?function=DIVIDENDS&symbol=IBM&apikey=demo`

## 2. API key usage (no secret values)

| Check | Result |
| --- | --- |
| `ALPHAVANTAGE_API_KEY` present in PoC environment? | **No** |
| Fallback | official `demo` key only |
| Secret committed? | **No** |

Demo-only success on IBM does **not** prove AAPL/MSFT/GOOGL or US-equity-wide readiness.

## 3. Live symbols attempted

| Symbol | Key mode | Result |
| --- | --- | --- |
| IBM | demo | **FETCH_PARSE_OK** (111 events) |
| AAPL / MSFT / GOOGL | skipped for live key path; demo probe for AAPL returned HTTP 200 + `Information` envelope | **not live-success** |

## 4. Live HTTP results (2026-09-11)

Executed:

```bash
./gradlew --no-daemon alphaVantageDividendPoc
curl -sS 'https://www.alphavantage.co/query?function=DIVIDENDS&symbol=IBM&apikey=demo'
```

| Call | HTTP | Body summary | SHA-256 |
| --- | ---: | --- | --- |
| `DIVIDENDS` IBM + demo | 200 | `symbol` + `data[]` (111 rows) | `49c7178089ee452f4afb56f2214f169494ee680ba1f085d0193876023c52c4d4` |
| `DIVIDENDS` AAPL + demo | 200 | provider **Information** envelope | Fail-Closed (not success) |

Live Gradle PoC (`ALPHAVANTAGE_API_KEY` absent): IBM/demo **FETCH_PARSE_OK**; `fetchedAt=2026-09-11T14:54:23.079594Z`; payload bytes `23420`.

**Fail-Closed:** HTTP 200 + `Error Message` / `Information` / `Note` is **not** success. No mock fallback.

## 5. Raw JSON structure

Observed live / official shape:

```text
{
  "symbol": "<TICKER>",
  "data": [
    {
      "ex_dividend_date": "YYYY-MM-DD",
      "declaration_date": "YYYY-MM-DD" | "None",
      "record_date": "YYYY-MM-DD" | "None",
      "payment_date": "YYYY-MM-DD" | "None",
      "amount": "<decimal-string>"
    }
  ]
}
```

**Absent from response:** currency, dividend type/REGULAR/SPECIAL, revision id, updated timestamp, per-row publication timestamp / knownAt.

### 5.1 Fixture provenance (`av-dividends-ibm-sanitized.json`)

Path: `src/test/resources/dividend/poc/av-dividends-ibm-sanitized.json`

| Claim | Status |
| --- | --- |
| Purpose | unit / Fail-Closed schema fixture only |
| Nature | **synthetic / sanitized schema fixture** |
| Live Alpha Vantage payload? | **No** |
| Treat values as provider-measured evidence? | **No** |
| Zero-amount row | artificial QA case (keep raw zero; do not delete) |
| Duplicate ex-date rows | artificial QA case (do not collapse; no latest-wins) |
| Substitute for live success? | **No** |

## 6. Date field evaluation

| Field | Provider key | Evaluation |
| --- | --- | --- |
| exDate | `ex_dividend_date` | Required calendar date string. Present on all live IBM rows. Stored as `LocalDate`. |
| declarationDate | `declaration_date` | Optional. Live IBM: many rows use sentinel `"None"` (87/111). Parsed to `null`; **not** invented. |
| recordDate | `record_date` | Optional. Same `"None"` pattern. |
| paymentDate | `payment_date` | Optional. Same `"None"` pattern. |

### 6.1 Optional-date absence evidence (Fail-Closed)

| Expression | Accepted as missing? | Evidence |
| --- | --- | --- |
| field absent | **Yes** → `null` | JSON schema allows omitting optional keys; parser treats missing key as absent |
| JSON `null` | **Yes** → `null` | Standard JSON null; not a string sentinel |
| string `"None"` | **Yes** → `null` | **Live IBM demo payload** (2026-09-11): 87/111 rows used exact `"None"` on declaration/record/payment. Official DIVIDENDS example uses the same field names; live is the primary string-sentinel evidence for this PoC |
| empty string `""` | **No** (Fail-Closed) | Not observed in live IBM DIVIDENDS payload; not confirmed as provider absence |
| string `"null"` | **No** (Fail-Closed) | Not observed in live IBM payload |
| string `"N/A"` | **No** (Fail-Closed) | Not observed in live IBM payload |
| string `"0000-00-00"` | **No** (Fail-Closed) | Not observed in live IBM payload |

Do **not** accept placeholders merely because they are “common elsewhere”. Unconfirmed strings must not be silently normalized to missing dates.

**declarationDate ≠ knownAt.** LocalDate alone must not become Instant via UTC/ET midnight, market open/close, or fetchedAt.

## 7. amount evaluation

| Check | Result |
| --- | --- |
| Representation | JSON string → `BigDecimal` lossless (no Double) |
| Negative | rejected (Fail-Closed) |
| Zero | kept if present (not auto-deleted) |
| Currency coupling | amount alone does **not** imply USD |

## 8. currency evaluation

| Question | Finding |
| --- | --- |
| Does DIVIDENDS return currency? | **No** |
| Infer USD from providerSymbol / US listing / amount format? | **Forbidden** |
| Status | `CurrencyResolutionStatus.UNRESOLVED_FROM_DIVIDENDS` |
| Map to `DividendEvent` while unresolved? | **Forbidden** |

Independent mapping blocker (same principle as price PoC §2.1.1).

## 9. dividend type evaluation

| Question | Finding |
| --- | --- |
| Type field in response? | **No** |
| Default to REGULAR? | **Forbidden** |
| Treat silence as Provider-asserted UNKNOWN? | **No** (absence ≠ explicit UNKNOWN) |
| Status | `DividendTypeResolutionStatus.UNRESOLVED_FROM_DIVIDENDS` |

## 10. historical knownAt evaluation

| Candidate | Verdict |
| --- | --- |
| per-row publication timestamp | **Absent** |
| `declaration_date` | LocalDate only → **not** knownAt |
| fetchedAt | possession PIT only |
| invented Instant from date | **Forbidden** |

**Result: `HistoricalKnownAtStatus.UNRESOLVED_UNUSABLE`**

## 11. PIT Grade evaluation

Data Contract §3.3 Grades A/B/C maintained.

| Claim | Grade |
| --- | --- |
| Alpha Vantage `DIVIDENDS` alone | **Grade C** only |
| Upgrade to A/B without primary publication evidence? | **Forbidden** |

Grade C history lists are unsuitable as advance-knowledge inputs for QDR / Dividend Capture.

## 12. future declared events

Official docs: API returns historical **and future (declared)** dividends.

| Observation | Detail |
| --- | --- |
| Live IBM demo (2026-09-11) | `eventsWithExAfterUtcToday=0` in this snapshot |
| Capability | Future declared rows are in-scope for the API text; absence in one snapshot does not disprove capability |
| PIT rule | Current response showing a future event ≠ that event was known at a past `decisionAt` |
| Mutations | amount/date change or cancellation may occur later; do not back-apply current future rows |

## 13. revision / correction / cancellation

| Check | Finding |
| --- | --- |
| Revision identity / updated timestamp | **Absent** |
| Duplicate ex-date rows in live IBM | not observed in this snapshot |
| Fixture duplicates | kept as-is; **no latest-wins** |
| CANCELLED / CORRECTED inference | **Forbidden** without provider evidence |

## 14. SecurityId mapping

Provider symbol is an external identifier only.

- No `SecurityId` on PoC models
- No ticker→`SecurityId` factory
- Existing `SecurityIdentifier` boundary unchanged

**Mapping blocker:** `SecurityIdMappingStatus.FORBIDDEN_FROM_PROVIDER_SYMBOL`

## 15. DividendEvent mapping blockers (summary)

Current `DividendEvent` requires: `securityId`, `exDate`, `amountPerShare`, `currency`, `dividendType`, `knownAt`, `ingestedAt`, `source`.

| Required field | From DIVIDENDS alone? |
| --- | --- |
| securityId | **No** (symbol ≠ SecurityId) |
| exDate | Yes (`ex_dividend_date`) |
| amountPerShare | Yes (`amount` as BigDecimal) |
| currency | **No** → UNRESOLVED |
| dividendType | **No** → UNRESOLVED (do not default REGULAR) |
| knownAt | **No** → UNRESOLVED_UNUSABLE |
| ingestedAt | system possession only (related to fetchedAt after success) |
| source | can label provider, but insufficient alone |

**DividendEvent mapper: not implemented.** All blockers independent; resolving one does not authorize mapping.

## 16. fetchedAt meaning

Stamped only after:
1. HTTP success
2. non-empty body
3. parse success
4. requested symbol matches payload symbol
5. required response structure (`symbol` + `data`) confirmed

Failure paths never stamp fetchedAt. fetchedAt ≠ historical knownAt.

## 17. Fail-Closed coverage (local tests)

Local HttpServer + synthetic sanitized fixture cover:
valid parse, HTTP 4xx/5xx, empty body, malformed JSON, HTTP 200 Error/Information/Note, missing `data`, symbol mismatch, malformed ex/declaration/record/payment/amount, negative amount reject, zero kept, optional `"None"` / JSON null / field absent → null, unconfirmed sentinels (`N/A`, `0000-00-00`, empty, `"null"`) Fail-Closed, currency/type unresolved, knownAt not invented, no SecurityId conversion, duplicate rows not collapsed, fetchedAt only after success.

## 18. Full history constraints

Live IBM demo returned **111** events spanning `1999-02-08` … `2026-08-10` in this run.

| Open point | Note |
| --- | --- |
| Completeness guarantee | Not proven (coverage / corporate-action completeness unknown) |
| Pagination | Not documented for DIVIDENDS in the reviewed docs text |
| Rate limits / entitlement | Free/demo limits apply; Information envelope Fail-Closed |

Even with long history, knownAt + currency + type + SecurityId blockers remain.

## 19. License / terms

Not fully audited. Free key registration, rate limits, redistribution, and commercial terms require separate review before production adoption.

## 20. Data Contract change needed?

**No weakening of `DividendEvent`.**

No code change to `DividendEvent` / `DividendType` in this PoC.

Residual Critical: provider-proven historical `knownAt` and explicit currency evidence remain unsolved for dividend rows. Do not make `knownAt` / `currency` optional to ingest Alpha Vantage rows.

## 21. Remaining Critical / High

| Severity | Item |
| --- | --- |
| Critical | historical knownAt UNRESOLVED_UNUSABLE (`declaration_date` ≠ knownAt) |
| Critical | currency UNRESOLVED from DIVIDENDS alone |
| Critical | dividendType UNRESOLVED (no REGULAR default) |
| Critical | SecurityId not derivable from provider symbol |
| Critical | AAPL/MSFT/GOOGL not live-verified without personal API key |
| High | revision/cancellation identity absent (no latest-wins allowed) |
| High | future declared rows must not be back-applied to past decisions |
| High | full-history completeness / license terms not fully reviewed |
| High | any future currency join needs separate provenance + identifier alignment |

## 22. Verdicts

| Lens | Verdict | Reason |
| --- | --- | --- |
| SE | PARTIAL | Boundary design sound; live readiness blocked by key + knownAt + currency + type + SecurityId |
| Programmer | PASS | Minimal PoC client/parser/tests; no DividendEvent mapper |
| Data Integrity | PARTIAL | Raw fields preserved; no invented knownAt/currency/REGULAR; duplicates not collapsed |
| QA | PASS | Local Fail-Closed suite + live demo result recorded separately |

**Overall: PARTIAL**

Acquisition success alone is not PASS. This PoC does **not** establish QDR/Dividend Capture validity, historical PIT completeness, or backtest readiness.

## 23. Next step?

Conditional only:
1. Re-run live with `ALPHAVANTAGE_API_KEY` (still without mapping to `DividendEvent`)
2. Or evaluate a source that provides defendable publication timestamps **and** currency/type evidence (or verified joins)

Do **not** proceed to QDR / Dividend Capture / Quality / Backtest from this PoC alone.
