# Alpha Vantage Price Venue Semantics Gate

**Date (UTC):** 2026-09-16  
**Investigation date (UTC):** 2026-09-16  
**Repository:** `siyokugo-rgb/us-stock-investment-assistant`  
**Review type:** Gate Review only（文書調査。Kotlin / AV client / MIC / VenueEvidence / SecurityId / DailyPrice / Backtest 実装なし）  
**Baseline `origin/main` HEAD:** `d9db10c7af0cd0f5b7640e522ae3c8e7c57f9f14`  
**PR #24:** MERGED（`mergedAt=2026-09-16T05:05:37Z`）  
**PR #24 final HEAD（ancestor verified）:** `8fba79d981915c5487b588cf98ac04312ee7d511`

| Gate | Verdict |
| --- | --- |
| TIME_SERIES_DAILY US venue semantics | **UNRESOLVED**（`PRICE_VENUE_SEMANTICS_UNRESOLVED`） |
| TIME_SERIES_DAILY foreign qualified symbol semantics | **PARTIAL**（公式例は「traded in {exchange}」ラベルまで。volume/session/MIC 契約は未確定） |
| AV Price ↔ venue-level FIGI | **NO-GO** |
| AV Price ↔ Composite FIGI | **UNVERIFIED** |
| MIC mapping PoC necessity | **DEFER**（AV series 側 semantics が未解決のままでは MIC 追加でも listing Price join は開かない） |
| Trading Currency | **UNSOLVED** |
| SecurityId | **NO-GO** |
| DailyPrice | **NO-GO** |
| Forward Research | **CONDITIONAL GO**（維持） |
| Real Backtest | **NO-GO**（維持） |

関連: [`venue-listing-identity-gate-review.md`](venue-listing-identity-gate-review.md)、[`forward-security-price-join-gate-review.md`](forward-security-price-join-gate-review.md)  
続編（PRICE source 再選定）: [`forward-price-source-reselection-gate.md`](forward-price-source-reselection-gate.md)

---

## 0. Baseline / merge confirmation

| Check | Result |
| --- | --- |
| `git fetch` + `checkout main` + `pull --ff-only` | OK |
| local `main` == `origin/main` | OK (`d9db10c7…`) |
| PR #24 state | **MERGED** |
| `8fba79d…` is ancestor of `main` | OK |

---

## 1. Development stage

```text
Forward Self-Archive
  → Security Master raw archive (OpenFIGI)
  → Price raw archive (Alpha Vantage TIME_SERIES_DAILY)
  → OpenFIGI request provenance
  → ProviderSymbolBindingEvidence (CANDIDATE / AMBIGUOUS / INELIGIBLE)
  → Venue / Listing Identity Gate Review（PR #24）
  → 【本レビュー】Alpha Vantage Price Venue Semantics Gate
```

**今回の問い:** `TIME_SERIES_DAILY` の price series を、specific exchange listing / consolidated / composite / regional-global / provider aggregation のどの粒度として **公式契約上** 安全に解釈できるか。

**まだ進まないもの:** AV client 変更、MIC 実装、VenueEvidence、SecurityId、DailyPrice、currency 推測、PriceSecurityJoinCandidate、DB、Backtest、Android。

---

## 2. What exists on main (facts only)

### 2.1 PRICE archive（Alpha Vantage）

- Endpoint: `GET /query`、`function=TIME_SERIES_DAILY`（raw only；`TIME_SERIES_DAILY_ADJUSTED` は対象外）
- `requestKey`: `GET|/query|function=TIME_SERIES_DAILY|symbol={SYMBOL}|outputsize={compact|full}`
- provider symbol は **request provenance** のみ（SecurityId / FIGI / MIC / currency ではない）
- Meta Data 例（fixture / 公式 shape）: Information / Symbol / Last Refreshed / Output Size / **Time Zone**（例 `US/Eastern`）
- Bars: date → open / high / low / close / **volume** のみ。currency / exchange / MIC フィールドなし

### 2.2 Binding

- `ProviderSymbolBindingEvidence` は AV symbol ↔ OpenFIGI TICKER → unique venue-level FIGI **candidate** まで
- AV PRICE series の venue semantics 整合は **未証明**（PR #24 §5.6）

---

## 3. Official Alpha Vantage sources（investigation date: 2026-09-16）

一次根拠のみ（非公式ブログ / StackOverflow は判定根拠にしない）:

| Source | Role |
| --- | --- |
| [Alpha Vantage API Documentation](https://www.alphavantage.co/documentation/) | TIME_SERIES_DAILY / DAILY_ADJUSTED / GLOBAL_QUOTE / SYMBOL_SEARCH / OVERVIEW / LISTING_STATUS / MARKET_STATUS / Core Time Series intro |
| [Support / FAQ](https://www.alphavantage.co/support/) | raw vs adjusted 調整方法；realtime/delayed 規制の概要リンク |
| [Realtime data policy](https://www.alphavantage.co/realtime_data_policy/) | realtime / 15-minute delayed US 市場データの規制・ライセンス説明（**TIME_SERIES_DAILY EOD venue 契約ではない**） |
| Official demo payloads（docs 掲載 URL、`apikey=demo`） | OVERVIEW IBM；SYMBOL_SEARCH `tesco` / `BA`；GLOBAL_QUOTE IBM；LISTING_STATUS CSV；MARKET_STATUS |

---

## 4. TIME_SERIES_DAILY — official wording

公式（Documentation `#daily`）:

- returns **raw (as-traded)** daily time series（date, daily open/high/low/close, daily volume）of the **global equity specified**
- Required `symbol`: 「The name of the equity of your choice. For example: `symbol=IBM`」
- Parameters: `function` / `symbol` / optional `outputsize` / optional `datatype` / `apikey`
- **exchange / MIC / consolidated / composite / venue を指定するパラメータは存在しない**
- Adjusted が必要なら別 API: `TIME_SERIES_DAILY_ADJUSTED`

Examples ラベル（公式）:

| Example symbol | Official label |
| --- | --- |
| `IBM` | Sample ticker traded in the United States |
| `TSCO.LON` | Sample ticker traded in UK - London Stock Exchange |
| `SHOP.TRT` | Canada - Toronto Stock Exchange |
| `GPV.TRV` | Canada - Toronto Venture Exchange |
| `MBG.DEX` | Germany - XETRA |
| `RELIANCE.BSE` | India - BSE |
| `600104.SHH` | China - Shanghai Stock Exchange |
| `000002.SHZ` | China - Shenzhen Stock Exchange |

**判定:** `symbol=IBM` は公式上 **「US で取引される ticker の例」** として提示される。  
NYSE listing symbol / US consolidated symbol / composite symbol / provider-specific aggregation のいずれとも **明記されていない** → **UNRESOLVED**。

---

## 5. US symbol exchange specificity

確認結果:

| Claim | Official evidence | Verdict |
| --- | --- | --- |
| US で `IBM.NYSE` / exchange-qualified suffix がある | TIME_SERIES_DAILY 公式例・パラメータに無し。LISTING_STATUS の US symbol にも suffix 無し（demo CSV） | **未確認 / 公式契約なし** |
| `IBM` = NYSE-only trades | 公式に無し | **FORBIDDEN 推論**（「IBM は NYSE 上場だから」禁止） |
| `IBM` = consolidated tape | 公式に無し | **UNRESOLVED** |
| TIME_SERIES_DAILY に exchange filter | 無し | **不可** |

Core Time Series intro の「US markets … NBBO quotes/trades/volumes … across exchanges, dark pools, and FINRA TRFs」は **US 市場データスイート一般**の記述であり、`TIME_SERIES_DAILY` の日足が consolidated であると **endpoint 契約として明記していない**。  
Realtime policy は realtime/delayed のライセンス説明であり、EOD daily venue semantics の定義ではない。

**US symbol exchange specificity:** **UNRESOLVED**（公式に exchange-specific 指定手段も、consolidated 断定も無い）。

---

## 6. Foreign qualified symbols（suffix）

公式例は suffix 付き symbol を **特定の取引所名付き「traded in …」** として提示する。

| Suffix（例） | Official association in docs examples |
| --- | --- |
| `.LON` | UK - London Stock Exchange |
| `.TRT` | Canada - Toronto Stock Exchange |
| `.TRV` | Canada - Toronto Venture Exchange |
| `.DEX` | Germany - XETRA |
| `.BSE` | India - BSE |
| `.SHH` | China - Shanghai |
| `.SHZ` | China - Shenzhen |

**区別:**

| Question | Verdict |
| --- | --- |
| suffix は何のラベルか | 公式例では **「どの取引所で traded される sample ticker」** の識別子。MIC コードではない。ISO MIC との等価は未証明 |
| exchange / market / region / provider namespace のどれか | **provider の market/exchange qualifier（非 US 向け）** として PARTIAL。region alone ではない（例: Canada で TRT vs TRV を分けている） |
| US へ類推して `IBM`=NYSE-only と断定 | **禁止** |
| foreign suffix があれば listing Price join GO か | **NO**。OHLC/volume が exchange-only か、session/calendar、currency、MIC との契約が別途必要 |

**Foreign qualified symbol semantics:** **PARTIAL**（公式に market/exchange ラベル付き symbol 体系がある。venue-level FIGI / MIC / volume 粒度までの証明ではない）。

---

## 7. SYMBOL_SEARCH

公式（`#symbolsearch`）: keywords に対する best-matching symbols と **market information**、match scores。

公式 demo payload（`keywords=tesco` / `BA`）フィールド:

| Field | Example | Use for TIME_SERIES_DAILY listing identity? | Use for trading currency? |
| --- | --- | --- | --- |
| `1. symbol` | `TSCO.LON`, `BA` | symbol 候補 lookup のみ | no |
| `2. name` | company/instrument name | 補助 | no |
| `3. type` | Equity / Mutual Fund | 補助 | no |
| `4. region` | United States / United Kingdom / Frankfurt | **region 粒度**。MIC / listing venue ではない | no |
| `5/6. marketOpen/Close` | session clock hints | calendar 補助候補。Price venue 証明ではない | no |
| `7. timezone` | UTC± | timezone→currency / venue 推測 **禁止** | no |
| `8. currency` | USD / GBX / EUR | Search metadata。PRICE series との **identifier/provenance 整合契約なしでは DailyPrice.currency に使えない** | **未結線 → UNSOLVED 維持** |
| `9. matchScore` | ranking | search only | no |

**維持:** Search metadata ≠ Price series venue semantics。  
**Case 4:** metadata があっても price endpoint との意味接続が無い → **join 禁止**。

---

## 8. OVERVIEW / LISTING_STATUS / GLOBAL_QUOTE

### 8.1 OVERVIEW

公式: 「**company information**, financial ratios, and other key metrics」。

Demo `symbol=IBM`（公式 demo URL）主要フィールド例:

- `Symbol`, `AssetType`（Common Stock）, `Name`, `CIK`
- `Exchange` = `NYSE`
- `Currency` = `USD`
- `Country` = `USA`

| Classification | Verdict |
| --- | --- |
| A. issuer/company metadata | **主にここ**（公式 wording + CIK/Address/financial ratios） |
| B. security/listing metadata | Exchange/Currency は listing 寄りに見えるが、**price-series metadata とは未結線** |
| C. price-series metadata | **ではない**（TIME_SERIES_DAILY 応答に無い） |

**禁止:** `OVERVIEW.Exchange=NYSE` → `TIME_SERIES_DAILY` が NYSE listing / MIC=XNYS 価格であると断定。

**Currency:** OVERVIEW.Currency も company/overview metadata。現行 PRICE archive と同一 request provenance で結ぶ契約は無く、**Trading Currency = UNSOLVED 維持**（推測 join 禁止）。

### 8.2 LISTING_STATUS

公式: active/delisted **US stocks and ETFs** のリスト（asset lifecycle / survivorship 向け）。CSV 列: `symbol,name,exchange,assetType,ipoDate,delistingDate,status`。  
IBM 例: `exchange=NYSE`。  
→ US listing roster / exchange **名**の補助。TIME_SERIES_DAILY venue semantics の証明ではない。

### 8.3 GLOBAL_QUOTE

最新 price/volume。応答に exchange/MIC/currency 無し（demo）。EOD update 既定；realtime/delayed は entitlement。  
→ daily series venue 契約の代替にならない。

---

## 9. US equity price venue semantics（最重要）

| Candidate interpretation | Official proof on TIME_SERIES_DAILY? |
| --- | --- |
| NYSE-only trades | **No** |
| Consolidated tape | **No** |
| Exchange aggregate（特定 exchange） | **No** |
| Provider aggregate | **明記なし**（否定も証明も不可） |
| 「global equity」generic US ticker | 公式は「global equity specified」+ US traded sample のみ → **venue 粒度は UNRESOLVED** |

**最終:** `PRICE_VENUE_SEMANTICS_UNRESOLVED`  
OpenFIGI に `micCode=XNYS` を付けても、AV `symbol=IBM` series が XNYS listing price であることは **自動証明されない**（PR #24 Case 13 維持）。

---

## 10. OpenFIGI join（venue-level FIGI）

現行:

```text
AV symbol → OpenFIGI TICKER request → unique venue-level FIGI candidate
```

昇格先:

```text
AV PRICE series → specific venue-level FIGI listing price
```

| Condition | Status |
| --- | --- |
| unique venue-level FIGI + provenance | OpenFIGI 側 candidate は可（PR #24） |
| AV series がその venue listing の OHLCV である公式契約 | **欠落** |
| MIC 追加で AV 側が埋まるか | **No** |

**AV Price ↔ venue-level FIGI:** **NO-GO**

---

## 11. Composite FIGI 可能性

もし AV daily が consolidated / country composite なら Composite FIGI の方が概念的に近い可能性はある。

ただし公式 AV は TIME_SERIES_DAILY を consolidated / composite と **明記していない**。

**「近い」≠ 採用。**  
**AV Price ↔ Composite FIGI:** **UNVERIFIED**（一致確認不可のため候補採用禁止）

---

## 12. Volume semantics

公式: 「daily volume」とだけ記載。  
exchange-specific / consolidated / provider aggregate の区別なし。  
OHLC と volume で粒度が異なるという公式注記もなし。

**Volume semantics:** **UNRESOLVED**  
→ venue-specific DailyPrice claim は **禁止**（または PARTIAL 未満；本 Gate では DailyPrice **NO-GO**）。

---

## 13. Trading calendar / session

| Endpoint | Official session control |
| --- | --- |
| `TIME_SERIES_INTRADAY` | `extended_hours` で RTH vs pre/post を明示（US: 9:30–16:00 vs 4:00–20:00 ET） |
| `TIME_SERIES_DAILY` | **session パラメータなし**。RTH-only / extended 含む / provider cutoff の公式断定なし |
| Meta `Time Zone` | 例 US/Eastern（fixture）。currency/venue 推測禁止 |
| `MARKET_STATUS` | region 単位の open/close（US primary_exchanges: NASDAQ, NYSE, AMEX, BATS）。daily bar の session 定義ではない |

**Session/calendar semantics（daily）:** **UNRESOLVED**（calendar 実装は今回禁止）

---

## 14. Corporate action boundary（venue 主目的の隣接）

| Endpoint | Official meaning |
| --- | --- |
| `TIME_SERIES_DAILY` | **raw (as-traded)** OHLCV only |
| `TIME_SERIES_DAILY_ADJUSTED` | raw OHLCV **plus** adjusted close + historical split/dividend events（Premium trending） |

Support FAQ: adjusted 系は splits と cash dividends で OHLC **and volume** を調整；raw オプションも提供。

**混同禁止:** 現行 PRICE archive は raw daily のみ。adjusted を raw に混ぜない。  
CA 実装は今回しない。

---

## 15. Acceptance matrix

| Case | Inputs | Verdict |
| --- | --- | --- |
| 1 | 公式が US price を specific exchange と明記 | **未該当**（明記なし）→ venue-specific join 候補にならない |
| 2 | 公式が consolidated market と明記 | **未該当** → specific venue FIGI join 禁止は維持；composite 採用も不可（未明記） |
| 3 | 公式が region だけ示す | SYMBOL_SEARCH `region` / MARKET_STATUS region → **venue unresolved** |
| 4 | SYMBOL_SEARCH region/currency あり、price との意味接続なし | **join 禁止** |
| 5 | OVERVIEW.Exchange=NYSE、TIME_SERIES_DAILY semantics 不明 | **NYSE price と断定禁止** |
| 6 | foreign qualified symbol（例 `TSCO.LON`） | その market/exchange **ラベル付き symbol** として条件付き評価可（PARTIAL）。MIC/FIGI/volume/session は別途。US 類推禁止 |
| 7 | volume semantics 不明 | venue-specific DailyPrice claim **禁止** → DailyPrice **NO-GO** |
| 8 | unique venue-level FIGI + MIC + AV `symbol=IBM` only | AV semantics 未解決 → Price listing join **NO-GO** |
| 9 | timezone / US symbol から venue or currency 推測 | **FORBIDDEN** |

---

## 16. Final classification

| Item | Verdict |
| --- | --- |
| TIME_SERIES_DAILY US venue semantics | **UNRESOLVED** |
| TIME_SERIES_DAILY foreign qualified symbol semantics | **PARTIAL** |
| AV Price ↔ venue-level FIGI | **NO-GO** |
| AV Price ↔ Composite FIGI | **UNVERIFIED** |
| MIC mapping PoC necessity | **DEFER** |
| Trading Currency | **UNSOLVED** |
| SecurityId | **NO-GO** |
| DailyPrice | **NO-GO** |
| Forward Research | **CONDITIONAL GO** |
| Real Backtest | **NO-GO** |

---

## 17. Severity

| Item | Severity | Note |
| --- | --- | --- |
| Historical knownAt / CA PIT / Universe | **Critical** | Real Backtest |
| Trading currency | **Critical** | DailyPrice；TIME_SERIES_DAILY に無し；SEARCH/OVERVIEW の無契約 join 禁止 |
| **AV Price venue semantics（US daily）** | **High（独立 blocker 維持）** | 公式調査後も UNRESOLVED。MIC/FIGI では埋まらない |
| Volume / session semantics | **High** | venue-specific DailyPrice を阻む |
| Standardized MIC | **High** | DEFER；AV semantics 解決後または別 source 選定後に再評価 |
| Share class / ticker history | **High** | SecurityId 前 |

---

## 18. Next minimal work（1つ）

### 選択: **E. Price source再選定（文書のみ）**

理由:

- 本 Gate で公式 AV 資料を一次根拠として調査した結果、`TIME_SERIES_DAILY` US venue semantics は **UNRESOLVED のまま**
- MIC mapping / Composite FIGI / SecurityId Issuance は **AV series 側の欠落契約を埋められない**
- listing-specific または consolidated-proven DailyPrice が将来必要なら、**venue/consolidated semantics を公式に述べる price source** の再評価が最小の前進
- Forward Research は既存 AV raw archive + binding candidate で **CONDITIONAL GO 維持**可能（listing Price join は開かない）

**含めない:** AV client 変更、MIC 実装、Currency 実装、SecurityId、DailyPrice、Android。

**選ばなかった理由（要約）:**

| Option | Why not now |
| --- | --- |
| A. MIC mapping evidence PoC | AV semantics 未解決のため listing Price join を開けない → **DEFER** |
| B. Composite FIGI semantics PoC | AV が consolidated/composite と未証明 → **UNVERIFIED** のまま採用禁止 |
| C. Trading Currency source PoC | Critical だが、本 Gate の直接 blocker（venue semantics）とは別軸 |
| D. ShareClassEvidence PoC | SecurityId 前に必要になり得るが、Price venue 欠落を解消しない |
| F. SecurityId Issuance Gate | Price join / currency / class 未充足 → 時期尚早 |

---

## 19. Reviewer verdicts

| Role | Verdict | Note |
| --- | --- | --- |
| SE | **PASS (review)** | 公式のみで UNRESOLVED を確定；次作業が MIC ではなく source 再選定に収束 |
| Programmer | **PASS (no code)** | Kotlin 変更なし |
| Data Integrity | **PASS** | Search/OVERVIEW ≠ Price venue；FIGI/MIC で AV semantics を捏造しない；currency 推測禁止維持 |
| QA | **PASS** | PR #24 / PRICE archive / binding 契約と矛盾なし |

---

## 20. Merge advice

- Draft PR としてレビュー可
- **main へ自動 merge しない**
- 本文書は SecurityId / DailyPrice / Real Backtest / MIC 実装の許可書ではない
- Alpha Vantage TIME_SERIES_DAILY の **US listing-specific または consolidated 断定は不可**

---

## 21. References

- [`venue-listing-identity-gate-review.md`](venue-listing-identity-gate-review.md)
- [`forward-security-price-join-gate-review.md`](forward-security-price-join-gate-review.md)
- [`price-forward-archive-poc.md`](price-forward-archive-poc.md)
- [`price-data-poc.md`](price-data-poc.md)
- [`price-source-entitlement-review.md`](price-source-entitlement-review.md)
- [`security-master-acceptance-criteria.md`](security-master-acceptance-criteria.md)
- [`data-contract.md`](data-contract.md)
- Official sources in §3
