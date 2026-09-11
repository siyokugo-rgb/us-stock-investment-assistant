# US Equity Price Feasibility PoC

Phase -1 Feasibility PoC for US equity **daily OHLCV** acquisition.

This document is **not**:
- proof of long-horizon price source adoption
- proof that historical `knownAt` is solved
- proof that `DailyPrice.currency` is solved from this endpoint alone
- proof that backtests are valid
- proof of strategy effectiveness

Baseline `main`: `1b6d828dc009bc5f8879023e69eb512fe31c0d25`  
Run date (UTC): 2026-09-11

## 1. Provider / endpoint

| Item | Value |
| --- | --- |
| Provider | Alpha Vantage |
| Official docs | https://www.alphavantage.co/documentation/ |
| Function | `TIME_SERIES_DAILY` |
| Endpoint | `GET https://www.alphavantage.co/query` |
| Auth | query param `apikey` (never committed) |
| Env var | `ALPHAVANTAGE_API_KEY` |
| Output size | `compact` (latest 100 points; free+premium). `full` (20+ years) requires premium per official docs |

Official description (docs): returns **raw (as-traded)** daily time series (date, open, high, low, close, volume). Adjusted series is a **separate** API: `TIME_SERIES_DAILY_ADJUSTED` (premium-trending).

## 2. API key usage (no secret values)

| Check | Result |
| --- | --- |
| `ALPHAVANTAGE_API_KEY` present in PoC environment? | **No** |
| Fallback | official `demo` key only |
| Secret committed? | **No** |

Because the key was absent, live attempts used `demo` only. That does **not** prove AAPL/MSFT/GOOGL availability.

## 3. Live symbols attempted

| Symbol | Key mode | Intended role |
| --- | --- | --- |
| IBM | demo | docs historically used with demo |
| AAPL / MSFT / GOOGL | skipped | require real `ALPHAVANTAGE_API_KEY` |

## 4. Live HTTP results (2026-09-11)

Executed:

```bash
curl -sS 'https://www.alphavantage.co/query?function=TIME_SERIES_DAILY&symbol=IBM&outputsize=compact&apikey=demo'
./gradlew --no-daemon alphaVantageDailyPoc
```

| Call | HTTP | Body summary | SHA-256 |
| --- | ---: | --- | --- |
| `TIME_SERIES_DAILY` IBM + demo | 200 | provider **Information** envelope (demo key only; claim free key) | `e8ddc218d89ff77ad7e395782b4c4d28d10d328ae90c3ba597a8e7d3689b9585` |
| `TIME_SERIES_DAILY` AAPL + demo | 200 | same Information envelope | (not treated as success) |
| `TIME_SERIES_DAILY_ADJUSTED` IBM + demo | 200 | same Information envelope | (adjusted not available via demo in this run) |

**Fail-Closed:** HTTP 200 + `Information` / `Note` / `Error Message` is **not** success. No mock fallback. No fabricated bars.

Live Gradle PoC with absent key: **FAIL_CLOSED** for IBM/demo Information envelope (expected under current demo policy).

**Live re-run after currency/provenance doc fix:** **not performed** (not required; prior Fail-Closed live result retained).

## 5. Raw JSON structure (official / sanitized fixture)

Successful TIME_SERIES_DAILY shape (from official documentation examples; mirrored in the **synthetic schema fixture** below):

```text
Meta Data
  1. Information
  2. Symbol
  3. Last Refreshed
  4. Output Size
  5. Time Zone
Time Series (Daily)
  YYYY-MM-DD
    1. open
    2. high
    3. low
    4. close
    5. volume
```

Values are JSON strings. Date keys are calendar dates (`YYYY-MM-DD`), not instants.

**Currency field:** not present in this response shape.

### 5.1 Fixture provenance (`av-daily-ibm-sanitized.json`)

Path: `src/test/resources/market/poc/av-daily-ibm-sanitized.json`

| Claim | Status |
| --- | --- |
| Purpose | unit / Fail-Closed schema fixture only |
| Nature | **synthetic / sanitized schema fixture** |
| Live Alpha Vantage payload? | **No** |
| Treat numeric values as provider-measured evidence? | **No** |
| Zero-price row | artificial QA case for existing Data Contract rule “keep raw zero; do not newly reject” |
| Substitute for live success? | **No** |

## 6. Timezone

Meta field `5. Time Zone` is present in official examples as `US/Eastern`.

Interpretation retained as **provider metadata string**.

We do **not** infer from that alone that every bar is the NYSE/Nasdaq regular-session official close. Alpha Vantage documents the series as raw as-traded daily OHLCV; it does not, in the Daily endpoint text reviewed here, provide a per-bar session stamp proving exclusive regular-hours composition.

Timezone must **not** be used to infer price currency (e.g. US/Eastern → USD).

## 7. tradingDate evaluation

| Question | Finding |
| --- | --- |
| Date key meaning | Calendar date string on each series entry |
| Weekend rows | Not present in official compact examples; fixture has weekdays only. Live demo did not return a series to re-measure |
| Holiday rows | Not proven by this PoC |
| Extended hours included? | **Not proven / not asserted** |
| Regular session official close? | **Not auto-asserted** from “daily” alone |

PoC stores `tradingDate: LocalDate` from the key only.

## 8. Raw OHLCV evaluation

Parser requires open/high/low/close/volume; keeps `BigDecimal` / `Long` losslessly from strings.

Invariants aligned with `DailyPrice` where applicable:
- negative price rejected
- negative volume rejected
- high >= low
- open/close within [low, high]
- **zero prices accepted** (not newly rejected)

## 9. Adjusted boundary

| Series | Role |
| --- | --- |
| `TIME_SERIES_DAILY` | raw / as-traded candidate (official wording) |
| `TIME_SERIES_DAILY_ADJUSTED` | separate adjusted + split/dividend fields; premium-trending |

This PoC does **not** mix adjusted into raw bars. Adjusted live was not successfully retrieved with demo. No synthetic adjusted values.

## 10. historical knownAt evaluation

| Candidate | Verdict |
| --- | --- |
| per-row publication timestamp | **Absent** in TIME_SERIES_DAILY response |
| `3. Last Refreshed` | series-level refresh marker, **not** per-row historical knownAt |
| tradingDate / market close / 16:00 ET | **Forbidden** to invent knownAt |
| fetchedAt | possession PIT only |

**Result: `HistoricalKnownAtStatus.UNRESOLVED_UNUSABLE`**

**Mapping blocker A (independent):** no mapping into `DailyPrice` while historical knownAt is unresolved.

## 11. currency evaluation

| Question | Finding |
| --- | --- |
| Does TIME_SERIES_DAILY return currency? | **No** (official shape / fixture: date + OHLCV only) |
| Infer USD from providerSymbol looking “US-like”? | **Forbidden** |
| Infer USD because symbol is AAPL/MSFT/GOOGL/IBM? | **Forbidden** |
| Infer from symbol suffix / timezone / exchange guess? | **Forbidden** |
| Separate currency source (e.g. SYMBOL_SEARCH)? | **Out of scope this PoC**; if ever used, requires its own source/provenance and identifier alignment |
| Currency unresolved → map to DailyPrice? | **Forbidden** (Fail-Closed) |

**Result: `CurrencyResolutionStatus.UNRESOLVED_FROM_TIME_SERIES_DAILY`**

**Mapping blocker B (independent of knownAt):** `DailyPrice.currency` cannot be satisfied from TIME_SERIES_DAILY alone.

PoC raw models do **not** auto-fill USD (or any currency). No `DailyPrice` mapper is provided.

## 12. SecurityId mapping evaluation

Provider symbol is an external identifier only.

- No `SecurityId` field on PoC models
- No ticker→SecurityId factory
- Existing `SecurityIdentifier` boundary unchanged

**Mapping blocker C (independent):** SecurityId must not be invented from provider symbol.

## 13. DailyPrice mapping blockers (summary)

| Blocker | Status |
| --- | --- |
| historical knownAt | UNRESOLVED_UNUSABLE |
| currency evidence | UNRESOLVED_FROM_TIME_SERIES_DAILY |
| SecurityId | not derived from symbol |

All three must remain Fail-Closed. Resolving one does not authorize `DailyPrice` creation.

## 14. fetchedAt meaning

Stamped only after:
1. HTTP success
2. non-empty body
3. parse success
4. requested symbol matches payload symbol

Failure paths never stamp fetchedAt. fetchedAt ≠ historical knownAt.

## 15. Fail-Closed coverage (local tests)

Local HttpServer + synthetic sanitized fixture cover:
valid parse, malformed JSON, empty body, HTTP 4xx/5xx, HTTP 200 Error/Information/Note, missing time series, symbol mismatch, malformed OHLC, negative price/volume, high<low, open/close out of range, zero price kept, timezone retained, tradingDate retained, no SecurityId conversion, currency unresolved (no USD inference), fetchedAt only after success, no fetchedAt on failure.

## 16. Full history constraints

Per official docs:
- `outputsize=compact`: latest 100 points (free + premium)
- `outputsize=full`: 20+ years; **premium**

Even with full history, absence of per-row historical knownAt **and** absence of currency in the daily response remain blocking for PIT-safe `DailyPrice` ingestion.

## 17. License / terms

Not fully audited here. Free key registration, rate limits, redistribution, and commercial terms require separate review before production adoption. Unconfirmed points remain open.

## 18. Data Contract change needed?

**No code change to `DailyPrice` fields in this PoC.**

Minimal contract reinforcement applied in `docs/data-contract.md` **§2.1.1**:
- `DailyPrice.currency` requires explicit source evidence
- no inference from ticker / provider symbol / exchange / timezone
- if the price source lacks currency, a verified identifier/source join is required
- unresolved currency → Fail-Closed (no `DailyPrice`)

Do **not** weaken `DailyPrice.knownAt` or `DailyPrice.currency` to optional just to ingest Alpha Vantage rows.

## 19. Remaining Critical / High

| Severity | Item |
| --- | --- |
| Critical | historical knownAt unusable for Alpha Vantage daily rows (**mapping blocker A**) |
| Critical | TIME_SERIES_DAILY alone cannot supply `DailyPrice.currency`; USD inference forbidden (**mapping blocker B**) |
| Critical | no successful live series without personal/premium API key in this environment |
| High | `outputsize=full` premium gate for long history |
| High | regular-session exclusivity not provider-proven from Daily endpoint text alone |
| High | adjusted endpoint premium / unavailable under demo |
| High | license/redistribution terms not fully reviewed |
| High | any future currency join needs separate source/provenance + identifier alignment (SYMBOL_SEARCH etc. not implemented here) |

## 20. Verdicts

| Lens | Verdict | Reason |
| --- | --- | --- |
| SE | PARTIAL | Boundary design sound; live readiness blocked by key + knownAt + currency |
| Programmer | PASS | Minimal PoC client/parser/tests; no over-abstraction; no DailyPrice mapper |
| Data Integrity | PARTIAL | Raw/adjusted separated; knownAt/currency not invented; no SecurityId guess |
| QA | PASS | Local Fail-Closed suite + live Fail-Closed recorded separately; fixture not treated as live proof |

**Overall: PARTIAL (Fail-Closed live without key; fixture path green; DailyPrice mapping blocked)**

Acquisition success alone is not PASS. This PoC does **not** establish long-horizon source adoption, knownAt resolution, currency resolution, backtest validity, or strategy validity.

## 21. Next step?

Conditional only:
1. Re-run live with `ALPHAVANTAGE_API_KEY` (still without mapping to `DailyPrice`)
2. Or evaluate a provider that exposes defendable historical publication timestamps **and** currency evidence (or a verified join path)

Do **not** proceed to Quality / ROIC / FCF / QDR / Backtest / adjusted execution prices / SYMBOL_SEARCH currency join from this PoC alone.
