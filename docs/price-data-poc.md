# US Equity Price Feasibility PoC

Phase -1 Feasibility PoC for US equity **daily OHLCV** acquisition.

This document is **not**:
- proof of long-horizon price source adoption
- proof that historical `knownAt` is solved
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

## 5. Raw JSON structure (official / sanitized fixture)

Successful TIME_SERIES_DAILY shape (from official documentation examples; mirrored in sanitized fixture `src/test/resources/market/poc/av-daily-ibm-sanitized.json`):

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

## 6. Timezone

Meta field `5. Time Zone` is present in official examples as `US/Eastern`.

Interpretation retained as **provider metadata string**.

We do **not** infer from that alone that every bar is the NYSE/Nasdaq regular-session official close. Alpha Vantage documents the series as raw as-traded daily OHLCV; it does not, in the Daily endpoint text reviewed here, provide a per-bar session stamp proving exclusive regular-hours composition.

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

Therefore live/historical rows are **not** mapped into existing `market.DailyPrice` (which requires `knownAt`).

## 11. SecurityId mapping evaluation

Provider symbol is an external identifier only.

- No `SecurityId` field on PoC models
- No ticker→SecurityId factory
- Existing `SecurityIdentifier` boundary unchanged

## 12. fetchedAt meaning

Stamped only after:
1. HTTP success
2. non-empty body
3. parse success
4. requested symbol matches payload symbol

Failure paths never stamp fetchedAt. fetchedAt ≠ historical knownAt.

## 13. Fail-Closed coverage (local tests)

Local HttpServer + sanitized fixture cover:
valid parse, malformed JSON, empty body, HTTP 4xx/5xx, HTTP 200 Error/Information/Note, missing time series, symbol mismatch, malformed OHLC, negative price/volume, high<low, open/close out of range, zero price kept, timezone retained, tradingDate retained, no SecurityId conversion, fetchedAt only after success, no fetchedAt on failure.

## 14. Full history constraints

Per official docs:
- `outputsize=compact`: latest 100 points (free + premium)
- `outputsize=full`: 20+ years; **premium**

Even with full history, absence of per-row historical knownAt remains blocking for PIT-safe `DailyPrice` ingestion.

## 15. License / terms

Not fully audited here. Free key registration, rate limits, redistribution, and commercial terms require separate review before production adoption. Unconfirmed points remain open.

## 16. Data Contract change needed?

**No code change to `DailyPrice` in this PoC.**

Contract residual remains Critical: provider-proven historical `knownAt` for prices is still unsolved. Do not weaken `DailyPrice.knownAt` to optional just to ingest Alpha Vantage rows.

## 17. Remaining Critical / High

| Severity | Item |
| --- | --- |
| Critical | historical knownAt unusable for Alpha Vantage daily rows |
| Critical | no successful live series without personal/premium API key in this environment |
| High | `outputsize=full` premium gate for long history |
| High | regular-session exclusivity not provider-proven from Daily endpoint text alone |
| High | adjusted endpoint premium / unavailable under demo |
| High | license/redistribution terms not fully reviewed |

## 18. Verdicts

| Lens | Verdict | Reason |
| --- | --- | --- |
| SE | PARTIAL | Boundary design sound; live readiness blocked by key + knownAt |
| Programmer | PASS | Minimal PoC client/parser/tests; no over-abstraction |
| Data Integrity | PARTIAL | Raw/adjusted separated; knownAt not invented; no SecurityId guess |
| QA | PASS | Local Fail-Closed suite + live Fail-Closed recorded separately |

**Overall: PARTIAL (Fail-Closed live without key; fixture path green)**

Acquisition success alone is not PASS. This PoC does **not** establish long-horizon source adoption, knownAt resolution, backtest validity, or strategy validity.

## 19. Next step?

Conditional only:
1. Re-run live with `ALPHAVANTAGE_API_KEY` (still without mapping to `DailyPrice`)
2. Or evaluate a provider that exposes defendable historical publication timestamps

Do **not** proceed to Quality / ROIC / FCF / QDR / Backtest / adjusted execution prices from this PoC alone.
