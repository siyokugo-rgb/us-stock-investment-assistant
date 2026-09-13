# US Equity Stock Split / Reverse Split Source Research

Phase -1 feasibility research for **formal / public** US stock split and reverse-split corporate-action data.

This document is **not**:
- a production provider design
- proof that any source is PIT-safe for backtests
- a recommendation to scrape unofficial websites as a formal source

Research date (UTC): 2026-09-13  
Method: official docs, exchange fact sheets, vendor API docs, and published policy / terms pages (web search + page fetch). No live vendor entitlement was exercised for paid feeds.

### Verdict (short)

**No.** There is **no free, formal, structured, PIT-safe historical US stock-split feed** suitable as a ready-made backtest entitlement.

Closest free formal building blocks are **SEC EDGAR filings** (acceptance timestamps as disclosure `knownAt` candidates) and **public exchange / FINRA notices** (effective date + ratio + CUSIP in HTML/text). Both require substantial extraction, coverage gaps, and still do not ship historical as-of event versioning out of the box. Commercial vendors supply structured ex/effective dates and ratios, but free tiers lack durable PIT `knownAt` / correction history, and terms usually restrict redistribution / commercial productization.

---

## 1. Comparison table

| Source | Authority | Endpoint / URL | Split / reverse support | History depth | Ratio + orientation | Timestamps (eff / ex / ann / pub) | Corrections / cancellations | Permanent IDs | Free vs paid | Historical access | License / use constraints | Historical `knownAt` reconstructable? |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| **SEC EDGAR filings (8-K / exhibits)** | U.S. SEC | Filings + APIs: [EDGAR APIs](https://www.sec.gov/search-filings/edgar-application-programming-interfaces); full-text [EFTS FAQ](https://www.sec.gov/edgar/search/efts-faq.html); access policy [Accessing EDGAR Data](https://www.sec.gov/search-filings/edgar-search-assistance/accessing-edgar-data); Form 8-K blank [form8-k.pdf](https://www.sec.gov/files/form8-k.pdf) | **Yes, narrative** — typically Item **5.03** (charter amendment), sometimes **3.03** / **8.01** + EX-99 press release; **not** a structured CA feed | Electronic full-text search since ~**2001**; filings archive far earlier via EDGAR | Ratio usually in prose (“1-for-20”); orientation clear in text; **no standard schema field** | **Acceptance / filed** datetime on EDGAR; effective time often in body; announcement often in EX-99; **no dedicated ex-date field** | Via later 8-K / amendments (`/A`); must be discovered manually | **CIK** strong; ticker on header; **CUSIP** sometimes in text; no FIGI | **Free** (fair-access 10 rps; declared User-Agent) | Full public archive | Gov-created / public EDGAR content free to access & reuse per [Webmaster FAQ](https://www.sec.gov/about/webmaster-frequently-asked-questions) / dissemination policy; rate limits apply | **Partial** — filing acceptance ≈ public disclosure time for that filing; **not** a complete market-knowledge clock (exchange notice may differ; missing/late filings; NLP risk) |
| **Nasdaq Equity Corporate Action Alerts** | Nasdaq | Public HTML alerts e.g. [TraderNews ECA](https://www.nasdaqtrader.com/TraderNews.aspx?id=ECA2026-380); archive [Corporate Action Alerts](https://www.nasdaqtrader.com/Trader.aspx?cat_id=105&id=archiveheadlines); reverse-split issuer rules [Issuer Alert 2025-001](https://listingcenter.nasdaq.com/assets/RuleBook/Nasdaq/rules/Issuer_Alert_2025-001.pdf) | **Yes** (forward & reverse); reverse alerts often include ratio, effective date, new CUSIP | Public alert archive browsable; depth not formally guaranteed as a machine API | Ratio + reverse orientation usually explicit (“one-for-ten (1-10) reverse split”) | Alert **publication** date on page; **market effective** date in body; announcement via linked press release; ex-date not always separate | Later alerts / Daily List updates can revise; not a versioned API | **Symbol + new CUSIP** commonly; no FIGI | **Free to view** HTML; email alerts via Nasdaq Trader enrollment | Manual / HTML archive — **not** a documented free historical API | Nasdaq Trader content subject to Nasdaq terms; structured Daily List is a **licensed product** (below) | **Partial for recent events** if alert publish time retained; **weak for systematic multi-decade PIT** without self-archived snapshots |
| **Nasdaq Daily List** | Nasdaq | Product page [Daily List](https://www.nasdaqtrader.com/Trader.aspx?id=DailyListpD); agreements via Nasdaq Global Data products | **Yes** — dividends section includes cash dividends, stock dividends, **stock splits** | Stated history back to **1999** | Structured product fields (vendor files); CUSIP optional add-on | Corporate-action / dividend notification fields; Next Day Ex-Date summary | Product supports corporate-action updates (licensed feed) | Symbol; **CUSIP** available with separate CUSIP license | **Paid** licensed market-data product | FTP / secured website for subscribers | Requires Nasdaq data agreements; CUSIP redistribution needs ABA/S&P CUSIP license ([Daily List page](https://www.nasdaqtrader.com/Trader.aspx?id=DailyListpD)) | **Better if subscriber archives publish times**; product itself is operational CA feed, not a free PIT research dump |
| **NYSE Group Market Event Feed (MEF)** | NYSE / ICE | [MEF product](https://www.nyse.com/market-data/corporate-actions/market-event-feed); [fact sheet PDF](https://www.nyse.com/publicdocs/nyse/NYSE_Market_Event_Feed_Factsheet.pdf); [Corporate Actions hub](https://www.nyse.com/market-data/corporate-actions) | **Yes** — stock splits among 60+ CA types for NYSE / American / Arca / Texas listings | Fact sheet: query history for actions announced up to **~6 months** before effective date (not multi-decade free archive) | Structured events; filters by event type | **Effective Date** and **Publish Date** filters documented; CUSIP filter | Explicit **cancel/correct linking** (Revision ID / Related ID / Relation ID) | **Symbol + CUSIP** | **Paid** (contact `datasales@nyse.com`) | On-demand API for subscribers; limited stated lookback | NYSE proprietary data / vendor agreements; redistribution / non-display fees typical of NYSE market data | **Yes for subscription window** via publish date + revision chain; **not free**; deep history unclear from public fact sheet |
| **FINRA OTC Daily List** | FINRA (OTC / non-exchange) | Web UI [otce.finra.org dailyList](https://otce.finra.org/otce/dailyList); user guide [OTCE Daily List User Guide](https://www.finra.org/sites/default/files/OTCE_Daily_List_User_Guide.pdf); Rule [6490](https://www.finra.org/rules-guidance/rulebooks/finra-rules/6490); SEA Rule 10b-17 context | **Yes** for OTC — forward/reverse split fields in Daily List downloads | Web history from **2014-11-17** to present (per user guide) | Forward / reverse split ratio fields in ORF Daily List specs | Effective timestamps / announcement on Daily List; cancellations flagged | Updates & cancellations indicated on Daily List | Symbol; CUSIP in ORF files (licensed download path) | **Web view free** for browsing; machine download API is authenticated / member-oriented | Browser history since 2014-11-17 | FINRA copyrighted site; ORF file downloads require TRAQS credentials ([ORF Web API](https://www.finra.org/sites/default/files/ORF-web-api-specification-version-9.0a.pdf)) | **Partial for OTC only** — Daily List publication day ≈ market notice; **does not cover NYSE/Nasdaq listed universe** |
| **Company IR / press releases** | Issuer | Company IR sites; often mirrored as **8-K EX-99** on EDGAR | **Yes** when issuers disclose | Per-issuer; no universal archive | Ratio usually clear in PR prose | Announcement datetime on wire/IR; effective date often stated | Correction PRs / 8-K amendments | Ticker; CUSIP sometimes; inconsistent | Free to read; not a unified API | Fragmented | Copyrighted issuer content; reuse varies by IR terms; EDGAR copy falls under EDGAR reuse | **Partial per event** if PR timestamp + EDGAR acceptance captured; **not universe-complete** |
| **Polygon / Massive Splits API** | Massive (ex-Polygon) | `GET /v3/reference/splits` (legacy) / docs for stocks splits e.g. [Massive splits docs](https://massive.com/docs/rest/stocks/corporate-actions/splits); pricing [massive.com/pricing](https://massive.com/pricing) | **Yes**; reverse via `reverse_split` / `adjustment_type` | Free Basic: **2y** history; paid up to **20+y** (stocks plans) | `split_from` / `split_to` with documented orientation (e.g. 2-for-1 → to=2, from=1) | **`execution_date` only** in public schema; no announcement / knownAt | Not documented as versioned cancel/correct feed | Ticker (+ internal id); FIGI/CUSIP not on split row in public schema | Free Basic includes Corporate Actions (5 calls/min); paid tiers | Historical by plan depth | [Market Data ToS](https://polygon.io/legal/market-data-terms-of-service): no redistribute / commercial use without consent; individual plans non-professional | **No** — execution date ≠ when market could know; no historical announcement snapshot |
| **Tiingo Splits API** | Tiingo | [Splits docs](https://www.tiingo.com/documentation/corporate-actions/splits); endpoints `/tiingo/corporate-actions/splits` and `/tiingo/corporate-actions/{ticker}/splits` | **Yes** (stocks/ETFs/MFs); reverse via from/to | Tied to EOD history (**30+ years** claimed for EOD) | `splitFrom`, `splitTo`, `splitFactor=to/from` | **`exDate`**; declaration date **not** in split schema (unlike dividends) | **`splitStatus` `a`/`c`** (active/cancelled) | Ticker + Tiingo `permaTicker`; not CUSIP/FIGI on split row | Free Starter exists with EOD limits; splits endpoint marked **beta** (may need support enable); paid $30/$50 internal | Historical ticker endpoint | [Pricing](https://www.tiingo.com/pricing): Internal Use Only on listed plans; redistribution needs sales license ([Developer appendix](https://www.tiingo.com/documentation/appendix/developers)) | **Weak** — cancellation flag helps; **no announcement/knownAt**; beta access risk |
| **Alpha Vantage `SPLITS`** | Alpha Vantage | [Documentation](https://www.alphavantage.co/documentation/) `function=SPLITS`; example `.../query?function=SPLITS&symbol=IBM&apikey=demo` | **Yes** historical splits | “Historical” (multi-year typical of AV fundamentals; not formally dated like CRSP) | `split_factor` string e.g. `"4/1"` (orientation as new/old) | **`effective_date` only** | Not documented | Ticker only | Free ≤ **25 req/day** ([support](https://www.alphavantage.co/support/)); premium rate plans | Per-symbol history in response | [Terms](https://www.alphavantage.co/terms_of_service/): default **personal non-commercial**; commercial needs written agreement (`premium@alphavantage.co`) | **No** |
| **Intrinio split adjustments** | Intrinio | [Splits by Security](https://docs.intrinio.com/documentation/web_api/get_security_stock_price_adjustments_splits_v2) `.../prices/adjustments/splits` | **Yes** (as price adjustments) | Long EOD history on paid US EOD products ([pricing](https://intrinio.com/pricing)) | `split_ratio` + adjustment `factor` | Adjustment **`date`** (apply to prices before date); not announcement | Not a CA lifecycle feed | **Ticker, FIGI, CUSIP, ISIN** on security object | Paid (trial); starter plans “no redistribution or display” | Historical adjustments API | Plan-tier licensing; redistribution requires higher tier / agreement | **No** (adjustment date, not knownAt) |
| **Xignite GlobalCorporateActions** | FactSet / Xignite | `GetSplits` / `GetSplitsByExchange` — [API catalog](https://apis.io/apis/xignite/xignite-global-corporate-actions-api/); launch note [PR](https://www.prnewswire.com/news-releases/xignite-introduces-new-corporate-actions-cloud-api-301294143.html) | **Yes** | Vendor-claimed global CA history (commercial; not free) | `Numerator` / `Denominator` / `SplitRatio`; `ExDate` | Ex-date primary in schema; other CA dates vary by method | Commercial CA stack (event ids); details under license | IdentifierType supports multiple ID schemes | **Paid** | Historical by contract | Commercial redistribution / display under Xignite/FactSet contract | **Unclear from public schema alone**; ask vendor for announcement / as-of |
| **LSEG / Refinitiv Corporate Actions** | LSEG | Workspace CA fields ([guide](https://developers.lseg.com/en/article-catalog/article/workspace-corporate-actions-content-set-guide)); DataScope / RTH extractions ([tutorial](https://developers.lseg.com/en/api-catalog/refinitiv-tick-history/refinitiv-tick-history-rth-rest-api/tutorials/rest-api-tutorials/rest-api-tutorial-9--on-demand-corporate-actions-extraction)); product [Corporate Actions Data](https://www.lseg.com/en/data-catalogue/corporate-actions) | **Yes** (SSP share split etc.; CAP stock split in DSS) | Product claims history from **early 1970s** / 50+ years | Old/new share terms fields | **`TR.CAAnnouncementDate`, Ex, Record, Effective**; rescind flag | `TR.CAIsRescinded`; ISO 15022 MT564/568 messaging | RIC, CUSIP, etc. by entitlement | **Paid enterprise** | Deep historical | Strict commercial license; redistribution separately contracted | **Best commercial fit for knownAt** via announcement + messaging as-of — **not free** |
| **Bloomberg Corporate Actions** | Bloomberg | Enterprise CA APIs / OpenAPI sketches e.g. [Buyside CA API catalog](https://apis.io/apis/bloomberg-buyside-enterprise-solutions/bloomberg-buyside-enterprise-solutions-corporate-actions-api/) (`STOCK_SPLIT`, `REVERSE_SPLIT`) | **Yes** | Deep terminal/enterprise history | Ratio string (e.g. `2:1`) in schema sketches | Enterprise CA dates (announcement / effective — entitlement-specific) | Enterprise revision workflows | FIGI / ticker / BBID typical | **Paid** (Terminal / B-PIPE / Data License) | Historical by license | Bloomberg redistribution prohibited without Data License terms | **Likely yes under paid CA as-of products**; not free |
| **CRSP US Stock Databases** | CRSP / Morningstar Indexes | [CRSP overview](https://indexes.morningstar.com/research-data-products/crsp-us-stock-databases); typically via WRDS | Split-adjusted returns / corporate actions research files | **~100 years** research depth claimed | Research-quality factors; PERMNO tracking | Event dating for research; not exchange publish feed | Research database revisions | **PERMNO / PERMCO** (excellent longitudinal IDs) | **Paid** academic/commercial | Yes under subscription | Proprietary; no free redistribution | Research-grade chronology, but **not free**; `knownAt` still model-dependent |
| **Open community datasets (e.g. DoltHub stocks)** | Community republishers | e.g. [post-no-preference/stocks](https://www.dolthub.com/repositories/post-no-preference/stocks) (CC BY-SA 4.0) | Split table present | Varies; monthly update claims | Ratio tables | Typically ex/effective only | Weak / unclear | Ticker-centric | “Free” download | Yes | **CC BY-SA** share-alike; **provenance not authoritative**; upstream ToS may still bind scrapes | **No** for formal PIT |

---

## 2. Priority-channel notes

### 2.1 SEC EDGAR / official filings (priority 1)

- **Best free formal disclosure channel**, not a CA master file.
- Reverse splits frequently appear under **Item 5.03** with Certificate of Amendment exhibit; Item **3.03** may be cross-referenced; forward splits often via **Item 8.01** + EX-99.
- Structured APIs (`data.sec.gov` submissions / companyfacts) give **filing metadata / XBRL facts**, not normalized split events.
- Fair access: descriptive User-Agent + ≤10 req/s ([Accessing EDGAR Data](https://www.sec.gov/search-filings/edgar-search-assistance/accessing-edgar-data)).
- Reuse: SEC states government-created sec.gov / public EDGAR content is free to access and reuse ([Webmaster FAQ](https://www.sec.gov/about/webmaster-frequently-asked-questions)).
- **PIT angle:** `acceptanceDateTime` / filed timestamp is the strongest free official clock for “when this disclosure hit the public record,” but it is **not** equivalent to exchange effective trading adjustment knowledge, and extraction of ratio/effective date remains NLP / exhibit parsing.

### 2.2 Exchange notices (priority 2)

- **Nasdaq:** public Equity Corporate Action Alerts are practical for spot checks; **Daily List** is the structured historical product (1999+, paid, CUSIP licensing caveat).
- **NYSE MEF:** strongest **documented** exchange API for publish/effective filters and cancel/correct linkage — **paid**, ~6-month historical query window per public fact sheet, NYSE Group listings only.
- Neither exchange publishes a free, complete, multi-decade machine-readable split master with as-of versions.

### 2.3 Company IR press releases (priority 3)

- Useful corroboration and often the earliest public announcement.
- Not a controlled universe source; must be joined to EDGAR EX-99 / exchange alerts.
- Unsuitable alone for Fail-Closed backtests.

### 2.4 Commercial vendors (priority 4)

| Vendor | Free-tier reality | PIT / knownAt | Notes for PoC |
| --- | --- | --- | --- |
| Polygon/Massive | Free Basic includes corporate actions, **2y**, 5 rpm | Execution date only | Fine for adjustment smoke tests; **not** PIT-safe |
| Tiingo | Free Starter with EOD limits; splits API **beta** | Ex-date + cancel status | Better orientation fields; still no announcement as-of |
| Alpha Vantage | Free 25/day; `SPLITS` on free docs | Effective date only | Matches prior dividend PoC pattern; commercial ToS restrictive |
| Intrinio | Trial / paid | Adjustment date | Good IDs (FIGI/CUSIP); adjustment-centric |
| Xignite | Paid | Ex-date in public schema | Enterprise CA |
| LSEG/Refinitiv | Paid | **Announcement date fields exist** | Best documented commercial knownAt path |
| Bloomberg | Paid | Enterprise as-of | Industry default; expensive |

### 2.5 Open datasets (priority 5)

- Only consider when license is explicit (**CC0 / CC BY / CC BY-SA**) **and** provenance is acceptable.
- Community split tables are **not** formal market authorities and generally lack announcement as-of.
- **Do not** treat Yahoo/unofficial scrape mirrors as formal sources (out of scope by request).

---

## 3. What “PIT-safe historical split source” would require

For Fail-Closed backtests aligned with this repo’s data contract:

1. Stable security identity across CUSIP changes (reverse splits often **change CUSIP**).
2. Explicit **ratio orientation** (N-for-M forward vs reverse).
3. **Effective / ex** trading date for price adjustment.
4. **`knownAt`** = earliest time the market could know the event (announcement or exchange publish), not merely ex-date.
5. Immutable event versions with **cancel / correct** history (no latest-wins).
6. Clear license for **internal research** at minimum; redistribution if the product redistributes derived data.

**No free formal source currently satisfies (1)–(6) as a ready feed.**

---

## 4. Feasibility implications for this repo

| Path | Feasibility for Phase -1 PoC | PIT-safe backtests? |
| --- | --- | --- |
| Build splitter from **SEC 8-K + acceptance time** | Research-grade, high NLP cost, incomplete coverage | **Not yet** — only disclosure-time lower bound |
| Archive **Nasdaq public alerts** + **FINRA OTC Daily List** | Spot/universe-slice PoC only | **No** for listed+OTC full history |
| License **NYSE MEF** + **Nasdaq Daily List** | Operational CA quality for listed names | **Maybe prospectively** if publish stamps archived; deep history costly |
| Vendor **LSEG / Bloomberg CA** | Highest completeness | **Yes-capable** under paid entitlement + contract |
| Free vendor splits (AV / Polygon / Tiingo) | Easy smoke PoC for ex/effective ratios | **Explicitly no** for historical knownAt |

**Recommended PoC posture (research conclusion):** treat free vendor split endpoints as **adjustment-factor smoke tests only** (same honesty bar as dividend PoC: missing `knownAt` ⇒ not solved). Treat SEC acceptance timestamps + exchange publish dates as the **only free formal knownAt reconstruction candidates**, with Fail-Closed coverage holes.

---

## 5. Key citations

- SEC EDGAR APIs: https://www.sec.gov/search-filings/edgar-application-programming-interfaces
- SEC Accessing EDGAR Data (fair access): https://www.sec.gov/search-filings/edgar-search-assistance/accessing-edgar-data
- SEC Webmaster FAQ (reuse): https://www.sec.gov/about/webmaster-frequently-asked-questions
- Form 8-K: https://www.sec.gov/files/form8-k.pdf
- Nasdaq Daily List: https://www.nasdaqtrader.com/Trader.aspx?id=DailyListpD
- Nasdaq reverse-split issuer alert: https://listingcenter.nasdaq.com/assets/RuleBook/Nasdaq/rules/Issuer_Alert_2025-001.pdf
- Nasdaq Corporate Action Alerts archive: https://www.nasdaqtrader.com/Trader.aspx?cat_id=105&id=archiveheadlines
- NYSE MEF: https://www.nyse.com/market-data/corporate-actions/market-event-feed
- NYSE MEF fact sheet: https://www.nyse.com/publicdocs/nyse/NYSE_Market_Event_Feed_Factsheet.pdf
- FINRA OTC Daily List guide: https://www.finra.org/sites/default/files/OTCE_Daily_List_User_Guide.pdf
- FINRA Rule 6490: https://www.finra.org/rules-guidance/rulebooks/finra-rules/6490
- Polygon/Massive splits docs: https://massive.com/docs/rest/stocks/corporate-actions/splits
- Massive pricing: https://massive.com/pricing
- Massive market data ToS: https://polygon.io/legal/market-data-terms-of-service
- Tiingo splits: https://www.tiingo.com/documentation/corporate-actions/splits
- Tiingo pricing / internal use: https://www.tiingo.com/pricing
- Alpha Vantage docs: https://www.alphavantage.co/documentation/
- Alpha Vantage terms: https://www.alphavantage.co/terms_of_service/
- Intrinio splits: https://docs.intrinio.com/documentation/web_api/get_security_stock_price_adjustments_splits_v2
- LSEG CA product: https://www.lseg.com/en/data-catalogue/corporate-actions
- LSEG Workspace CA guide: https://developers.lseg.com/en/article-catalog/article/workspace-corporate-actions-content-set-guide
- CRSP: https://indexes.morningstar.com/research-data-products/crsp-us-stock-databases

---

## 6. Explicit answer to the gate question

### Is there a free formal PIT-safe historical split source suitable for backtests?

**No.**

Free formal channels (SEC EDGAR, public exchange/FINRA notices) can support a **reconstruction research PoC**, but they are not a ready PIT-safe historical split database. Free commercial tiers that return split ratios are **ex/effective-date adjustment feeds**, not historical market-knowledge feeds, and their terms usually forbid redistribution / unconstrained commercial use.
