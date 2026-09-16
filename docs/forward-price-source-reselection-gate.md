# Forward Price Source Re-selection Gate

**Date (UTC):** 2026-09-16  
**Investigation date (UTC):** 2026-09-16  
**Repository:** `siyokugo-rgb/us-stock-investment-assistant`  
**Review type:** Gate Review only（PRICE source 再選定。Kotlin / client / DailyPrice / SecurityId / Backtest 実装なし）  
**Baseline `origin/main` HEAD:** `e6699f76e637a043c3746b6291277309790c8f26`  
**PR #25:** MERGED（`mergedAt=2026-09-16T05:25:58Z`）  
**PR #25 final HEAD（ancestor verified）:** `e9f8f25bb733fab43d054d03011aa2b2bc55e4ed`

| Gate | Verdict |
| --- | --- |
| AV as PRICE **primary** for DailyPrice / listing join | **NO**（possession archive としては維持） |
| Massive / Polygon as Forward PRICE candidate | **CONDITIONAL YES**（SIP/consolidated 製品主張 + reference currency/MIC/FIGI。endpoint 文言は PARTIAL） |
| Tiingo as Forward PRICE candidate | **WEAK / PARTIAL**（raw+adj 分離と EOD cutoff は強い。US venue = provider composite 寄せ。currency on EOD meta **無し**） |
| EODHD as Forward PRICE candidate | **CONDITIONAL PARTIAL**（symbol-list `Currency` / delisted 強い。US は composite code。volume は split-adjusted） |
| Single-provider full DailyPrice GO | **NO** |
| Forward Research | **CONDITIONAL GO**（維持；AV archive 削除禁止） |
| Real Backtest | **NO-GO**（維持） |

関連: [`alpha-vantage-price-venue-semantics-gate.md`](alpha-vantage-price-venue-semantics-gate.md)、[`price-source-entitlement-review.md`](price-source-entitlement-review.md)

---

## 0. Baseline / merge confirmation

| Check | Result |
| --- | --- |
| `git fetch` + `checkout main` + `pull --ff-only` | OK |
| local `main` == `origin/main` | OK (`e6699f76…`) |
| PR #25 state | **MERGED** |
| `e9f8f25…` is ancestor of `main` | OK |

---

## 1. Purpose

Alpha Vantage `TIME_SERIES_DAILY` は:

- raw archive boundary としては利用可能
- US venue semantics = **UNRESOLVED**（PR #25）
- trading currency = **UNSOLVED**
- venue-level FIGI join = **NO-GO**

**今回の問い:** Forward Research 向け PRICE source として、AV より **意味契約が明確な** source があるか。  
「価格が取得できる」だけでは採用しない。

**禁止維持:** AV archive コード削除 / 大規模 refactor / ticker→SecurityId / currency 推測 / Real Backtest 自動 GO。

---

## 2. Candidates surveyed

| Source | Role in this gate |
| --- | --- |
| Alpha Vantage | baseline 比較対象（既存 PRICE archive） |
| Massive（旧 Polygon.io） | 主要再調査 |
| Tiingo | 主要再調査 |
| EODHD | 主要再調査 |
| Nasdaq Data Link WIKI | 参考のみ（discontinued；採用不可） |
| Exchange / institutional tapes | 技術比較用に分離（¥10k 運用外） |

---

## 3. Official sources（investigation date: 2026-09-16）

一次根拠のみ（ブログ比較サイトは採用判定根拠にしない。Tiingo blog は補助参照に留め、製品/ドキュメント/KB を一次とする）:

### Alpha Vantage
- [API Documentation](https://www.alphavantage.co/documentation/)（TIME_SERIES_DAILY）
- [`alpha-vantage-price-venue-semantics-gate.md`](alpha-vantage-price-venue-semantics-gate.md)（PR #25）

### Massive
- [Custom Bars (OHLC)](https://massive.com/docs/rest/stocks/aggregates/custom-bars)
- [Ticker Overview](https://massive.com/docs/rest/stocks/tickers/ticker-overview)
- [Daily Market Summary](https://massive.com/docs/rest/stocks/aggregates/daily-market-summary)
- [Stocks product](https://massive.com/stocks)（SIP / consolidated tape / Individual vs Business）
- [Pricing](https://massive.com/pricing)

### Tiingo
- [EOD product](https://www.tiingo.com/products/end-of-day-stock-price-data)
- [EOD documentation](https://www.tiingo.com/documentation/end-of-day)
- [EOD ingest KB](https://www.tiingo.com/kb/article/the-fastest-method-to-ingest-tiingo-end-of-day-stock-api-data/)
- [Splits documentation](https://www.tiingo.com/documentation/corporate-actions/splits)（permaTicker）
- [Pricing](https://www.tiingo.com/pricing)

### EODHD
- [EOD Historical Data API](https://eodhd.com/financial-apis/api-for-historical-data-and-volumes)
- [Exchanges / ticker list](https://eodhd.com/financial-apis/exchanges-api-list-of-tickers-and-trading-hours)
- [Delisted companies](https://eodhd.com/financial-apis/delisted-stock-companies-data-2)
- [Commercial vs personal](https://eodhd.com/financial-apis/commercial-vs-personal-license-use)
- [Pricing](https://eodhd.com/pricing)

---

## 4. HARD GATE findings（per provider）

### 4.1 Alpha Vantage（baseline）

| Gate | Finding | Verdict |
| --- | --- | --- |
| A. Identity | provider symbol provenance only；stable vendor id なし | FAIL for Security join |
| B. Venue | US daily は venue-specific / SIP / composite 未明記（PR #25） | **UNRESOLVED** |
| C. Currency | daily row に無し；SEARCH/OVERVIEW 無契約 join 禁止 | **UNSOLVED** |
| D. Session | daily に RTH/extended 契約なし | UNRESOLVED |
| E. Volume | daily volume 粒度未明記 | UNRESOLVED |
| F. raw/adjusted | DAILY = raw；ADJUSTED は別 endpoint | PASS（分離） |

**分類:** 既存 **possession / raw archive** 維持。Forward PRICE **primary にはしない**。

### 4.2 Massive

| Gate | Finding | Verdict |
| --- | --- | --- |
| A. Identity | aggregates は ticker。Ticker Overview が `composite_figi` / `share_class_figi` / `cik` / `primary_exchange`（ISO MIC 例 `XNAS`）/ `active` / `delisted_utc` を返す | **PARTIAL〜PASS（reference）**。venue-level FIGI は未返却 |
| B. Venue | 製品: Individual plans は **SIP-based end to end**；US tape を consolidated tape として説明。Custom Bars 本文は「qualifying trades」集約で **day bar に consolidated 文言は無い** | **PARTIAL**（製品 SIP 主張あり；endpoint 明示は弱い → PASS 扱いにしない） |
| C. Currency | bar に無し。`currency_name` = 「currency that this asset is **traded with**」（Ticker Overview） | **PARTIAL〜PASS（reference path）**。price row 単独では不可；同一 vendor join + provenance 必須 |
| D. Session | Custom Bars は pre / regular / after-hours をカバーと明記。RTH-only daily フィルタは未確認 | **PARTIAL** |
| E. Volume | SIP/全 venue + TRF を製品が強調。bar `v` は window volume；venue-only ではない想定 | **PARTIAL**（consolidated 寄せ） |
| F. raw/adjusted | `adjusted=false` → **NOT adjusted for splits**。default true は splits。dividend 調整の有無は docs 上 splits 中心 | **PASS（split raw 可）**；div 調整境界は PARTIAL |

**分類:** **FORWARD_PRICE_CANDIDATE（条件付き）** + 同一 vendor reference で identity/currency 補助。  
**RETROSPECTIVE_INELIGIBLE**（publication knownAt / revision ledger なし → Grade C）。

### 4.3 Tiingo

| Gate | Finding | Verdict |
| --- | --- | --- |
| A. Identity | ticker + meta `exchangeCode`；CA docs に **permaTicker**（delisted/recycle 向け stable id）。FIGI/MIC は EOD meta に無し | PARTIAL |
| B. Venue | 複数 exchange カバー。製品は multi-source **Composite Index** で洗浄・統合と説明 → **provider composite/aggregate** 寄せ。SIP/venue-specific の明記なし | **FAIL〜PARTIAL（UNRESOLVED に近い）** |
| C. Currency | EOD price / meta フィールドに trading currency **無し**（確認: ticker/name/exchangeCode/description/startDate/endDate） | **FAIL（EOD 経路）** |
| D. Session | Equities/ETFs **~5:30pm EST** 配信；corrections〜**8:00pm EST**。RTH vs extended の日足定義は未明記 | PARTIAL（cutoff は強い） |
| E. Volume | `volume` / `adjVolume` あり。venue vs consolidated 未明記 | UNRESOLVED |
| F. raw/adjusted | 同一応答に raw OHLC + adj* + `divCash` / `splitFactor`。CRSP 方式で split+dividend adj | **PASS（分離）** |

**分類:** raw archive / forward cutoff 研究には有用だが、venue+currency 契約不足のため **弱い FORWARD 候補**（primary 不適に近い）。  
KB: split/div 後に **history 再取得推奨** → revision あり得る → retrospective as-known **INELIGIBLE**。

### 4.4 EODHD

| Gate | Finding | Verdict |
| --- | --- | --- |
| A. Identity | `SYMBOL.EXCHANGE`（例 `AAPL.US`）。exchange-symbol-list に Code/Exchange/Currency/Isin。FIGI なし。US は **composite code**（複数 Operating MIC） | PARTIAL |
| B. Venue | `US` = composite（OperatingMIC 例 `XNAS, XNYS, OTCM, XCBO`）。行の `Exchange` は venue 名だが **EOD が venue-specific とは未明記** | **PARTIAL / UNRESOLVED for specific venue** |
| C. Currency | symbol list `Currency` = **「Trading currency of the listing」**（公式）。exchanges-list にも default Currency | **PARTIAL〜PASS（reference path）**。price row 自体には無し |
| D. Session | EOD daily；Trading Hours は別 API。日足 RTH-only 断定は弱い | PARTIAL |
| E. Volume | **volume is adjusted for splits**（公式）。OHLC は raw | **PARTIAL**（raw volume ではない） |
| F. raw/adjusted | OHLC raw；`adjusted_close` 別；volume split-adj | **PASS（OHLC）** / volume PARTIAL |

**分類:** **FORWARD_PRICE_CANDIDATE（条件付き・特に currency/delisted）**。venue-specific join や raw volume claim には不十分。  
Delisted 公式経路あり → survivorship 研究で Massive/AV より強い。  
Retrospective knownAt ledger なし → **RETROSPECTIVE_INELIGIBLE**。

---

## 5. Identity join vs existing main

現行 main:

```text
AV symbol → OpenFIGI TICKER → venue-level FIGI candidate
```

| Provider | Direct FIGI | MIC / exchange | Stable vendor id | Safe join note |
| --- | --- | --- | --- | --- |
| AV | no | no on daily | no | OpenFIGI 別経路のみ。Price venue 未解決のため listing join NO-GO |
| Massive | **composite_figi / share_class_figi**（Ticker Overview） | **primary_exchange ISO MIC** | ticker + figi refs | **同一 vendor** price↔reference が最安全。venue-level FIGI は別途 OpenFIGI。ticker alone → SecurityId **禁止** |
| Tiingo | no（EOD meta） | exchangeCode（非 MIC 断定不可） | **permaTicker**（CA/docs） | OpenFIGI 併用は ticker/exchange 曖昧性あり Fail-Closed |
| EODHD | no | OperatingMIC on exchanges-list；per-ticker Exchange 名；ISIN partial | exchange-qualified symbol | Currency 明示は強い。FIGI 無し。ticker alone 禁止 |

**禁止維持:** provider ticker だけで OpenFIGI 確定／SecurityId 発行。

---

## 6. Point-in-time

| Provider | Forward self-archive | Retrospective historical knownAt | Revision / correction |
| --- | --- | --- | --- |
| AV | YES（既存 PoC） | FAIL（Grade C） | ledger なし |
| Massive | YES（REST 取得可） | FAIL（`t` = window start ≠ publication） | ledger なし（確認範囲） |
| Tiingo | YES；公式 EOD cutoff あり → forward decisionAt 制約の **B-candidate** | FAIL（現在 history ≠ as-known） | split/div 後再取得推奨 → 書き換えリスク明示 |
| EODHD | YES | FAIL | ledger なし |

**現在取得した history を historical as-known にしない**（全候補共通）。

---

## 7. Survivorship / delisted

| Provider | Official delisted path | Note |
| --- | --- | --- |
| AV | UNKNOWN / 弱い | LISTING_STATUS は US roster；広範 delisted price entitlement 未確認 |
| Massive | `active=false` / All Tickers；Ticker Overview `delisted_utc` | Basic history **2y** 制限あり |
| Tiingo | permaTicker / endDate 等 | 広範 delisted completeness は完全証明せず → PARTIAL |
| EODHD | **`delisted=1` + EOD history** 公式 | **最強の retail survivorship 緩和候補** |

active-only を broad historical universe 対応としない。

---

## 8. Corporate action boundary

全候補とも split/dividend/adj を持ち得るが、**CA PIT 解決済みとはしない**。

| Provider | raw vs CA separation |
| --- | --- |
| AV | raw daily vs adjusted endpoint 分離 |
| Massive | `adjusted` flag（splits） |
| Tiingo | raw + adj fields + divCash/splitFactor 同一応答でもフィールド分離可 |
| EODHD | OHLC raw vs adjusted_close；volume は split-adj（混在注意） |

CA knownAt は別 Gate。

---

## 9. Required comparison matrix

値: PASS / PARTIAL / FAIL / UNKNOWN（UNKNOWN≠PASS）

| Requirement | AV | Massive | Tiingo | EODHD |
| --- | --- | --- | --- | --- |
| raw daily OHLCV | PASS | PASS（`adjusted=false`） | PASS | PASS（OHLC） |
| US venue semantics explicit | FAIL | PARTIAL（製品 SIP；endpoint 弱） | FAIL | PARTIAL（US composite） |
| volume semantics explicit | FAIL | PARTIAL | FAIL | PARTIAL（split-adj 明示） |
| session semantics explicit | FAIL | PARTIAL | PARTIAL（cutoff） | PARTIAL |
| stable security id | FAIL | PARTIAL（FIGI comps） | PARTIAL（permaTicker） | PARTIAL（exch-qualified + ISIN partial） |
| FIGI available | FAIL | PASS（composite/share class） | FAIL | FAIL |
| MIC / exchange evidence | FAIL | PASS（primary_exchange ISO） | PARTIAL（exchangeCode） | PARTIAL（OperatingMIC / Exchange） |
| explicit trading currency | FAIL | PARTIAL（`currency_name` traded with） | FAIL（EOD meta 無し） | PARTIAL〜PASS（symbol `Currency`） |
| delisted securities | UNKNOWN | PARTIAL | PARTIAL | PASS（公式経路） |
| raw vs adjusted separation | PASS | PASS | PASS | PASS（volume 例外） |
| forward self-archive suitable | PASS | PASS | PASS | PASS |
| retrospective knownAt | FAIL | FAIL | FAIL | FAIL |
| revision history | FAIL | FAIL | PARTIAL（再取得推奨の明示） | FAIL |
| personal/internal license | PASS（free/personal 寄り） | PASS（Individual） | PASS（Internal Use） | PASS（personal packages） |
| redistribution | FAIL（制約） | FAIL（Individual；Business 要） | FAIL（Internal；別契約） | FAIL（personal≠commercial） |
| monthly cost（公開） | FREE〜premium 高 | $0 / $29 / $79 / $199… | $0 / $30 / $50 | FREE / $19.99 / $99.99… |
| ¥10k account economics | FREE 帯は合理；premium 不適 | Starter+ は重い | Power $30 重い | $19.99 でも年コスト重い |

---

## 10. Adoption categories

| Source | Category |
| --- | --- |
| Alpha Vantage | **既存 raw possession archive（維持）**。Forward PRICE primary から降格 |
| Massive | **FORWARD_PRICE_CANDIDATE（条件付き）** + **IDENTITY_SUPPORT**（同一 vendor reference）。**RETROSPECTIVE_INELIGIBLE** |
| Tiingo | **WEAK FORWARD / IDENTITY_SUPPORT 弱**。currency FAIL。**RETROSPECTIVE_INELIGIBLE** |
| EODHD | **FORWARD_PRICE_CANDIDATE（条件付き；currency/delisted）**。venue/volume 制約。**RETROSPECTIVE_INELIGIBLE** |
| Institutional / exchange official | 技術比較用。¥10k では **REJECT（経済）** — 技術 FAIL ではない |

---

## 11. Required answers

| # | Question | Answer |
| --- | --- | --- |
| A | AV を PRICE primary 継続可能か | **DailyPrice / listing join primary としては NO**。possession evidence archive としては **YES（削除禁止）** |
| B | Massive は候補か | **YES（条件付き FORWARD_PRICE_CANDIDATE）** |
| C | Tiingo は候補か | **弱い PARTIAL**。venue/currency 不足で primary 不適 |
| D | EODHD は候補か | **YES（条件付き）** — 特に trading currency path と delisted |
| E | 最も現実的な Forward PRICE 構成 | **Massive raw daily aggs（`adjusted=false`）+ 同一 vendor Ticker Overview（currency_name / primary_exchange / FIGIs）**。AV archive は並行保持。OpenFIGI は venue-level 補助（Price 自動 join しない） |
| F | 1 source で成立するか | **DailyPrice 完全成立は NO**。Massive 1 vendor・2 endpoint が最短。SecurityId / knownAt / CA PIT は別 |
| G | Security Master 安全 join | Massive: share_class/composite FIGI + MIC。EODHD: exchange-qualified symbol + Currency + ISIN partial。いずれも **ticker alone 禁止**；SecurityId 自動発行 **NO-GO** |
| H | Trading Currency を同時解決できる候補 | **Massive `currency_name`** と **EODHD symbol `Currency`** が明示 path。AV/Tiingo EOD は不可。いずれも price row 直載ではない → provenance 付き join Gate が必要 |
| I | client PoC 実装価値 | **Massive raw archive PoC に価値あり**（文書契約が AV より前進）。Tiingo は currency 欠落で優先度低。EODHD は currency/delisted PoC 候補だが venue は複合 |
| J | 無ければ FAIL か | **PRICE source selection を全面 FAIL にはしない**。条件付き候補（Massive / EODHD）あり。ただし **DailyPrice GO ではない** |

---

## 12. ¥10,000 economics（技術と分離）

想定口座: **¥10,000**。

| Source | Economics |
| --- | --- |
| AV free | **FREE / ECONOMICALLY_REASONABLE**（制限付き） |
| AV premium | **ECONOMICALLY_UNSUITABLE** 寄り |
| Massive Basic $0 | FREE（history 2y） |
| Massive Starter $29/mo | **ECONOMICALLY_UNSUITABLE**（年≈$350 ≒ 口座の数倍） |
| Tiingo $0 / $30 / $50 | Free は合理；$30+ は **UNSUITABLE** |
| EODHD $19.99/mo | **BORDERLINE〜UNSUITABLE** |
| Institutional | **UNSUITABLE** |

高額 ≠ 技術 FAIL。経済と技術を分離記録。

---

## 13. Alpha Vantage handling

- AV TIME_SERIES_DAILY forward archive **削除しない**
- 大規模 refactor 禁止
- 役割: **raw possession evidence / provider-symbol research**
- listing-specific DailyPrice primary には使わない（PR #25 維持）

---

## 14. Final classification summary

| Item | Verdict |
| --- | --- |
| Forward Price primary recommendation | **Massive（条件付き）** |
| Secondary / currency-delisted aid | **EODHD（条件付き）** — 安易な cross-ticker join 禁止 |
| AV | possession only |
| Trading Currency | Massive/EODHD path で **候補あり**；未実装のため **UNSOLVED 維持** |
| SecurityId | **NO-GO** |
| DailyPrice | **NO-GO** |
| Forward Research | **CONDITIONAL GO** |
| Real Backtest | **NO-GO** |

---

## 15. Severity

| Item | Severity |
| --- | --- |
| Historical knownAt / CA PIT / Universe PIT | **Critical**（Real Backtest） |
| Trading currency（実装・provenance join） | **Critical**（DailyPrice） |
| AV venue semantics（primary 降格後も AV 利用時） | High |
| Massive day-bar consolidated 文言の endpoint 明示不足 | High（候補でも PARTIAL） |
| Cross-provider ticker join | High / Forbidden without identity contract |
| Share class / ticker history | High |

---

## 16. Next minimal work（1つ）

### 選択: **A. Chosen Price Source raw archive PoC（Massive）**

理由:

- 公式上、AV より **SIP/consolidated 製品契約・MIC・trading currency wording・FIGI reference** が明確
- 既存 AV archive は残し、**追加の PRICE domain archive** として最小 PoC が可能
- Currency / MIC は同一 vendor reference を後続で結ぶ前提（今回は raw possession のみ）

**含めない:** SecurityId、DailyPrice mapper、currency 自動 join、AV 削除、Backtest、Android。

**選ばなかった理由:**

| Option | Why not |
| --- | --- |
| B. Trading Currency Evidence PoC | Massive/EODHD path は有望だが、先に raw possession boundary を固定した方が Fail-Closed |
| C. MIC / Venue PoC | Massive primary_exchange は reference；Price archive 無しでは不完全 |
| D. ShareClassEvidence | SecurityId 前に必要だが本 blocker の最短ではない |
| E. 再調査継続 | 主要 retail 候補の公式比較は本 Gate で十分 |
| F. 成立不能として設計再検討 | 条件付き候補が存在するため premature |

---

## 17. Reviewer verdicts

| Role | Verdict | Note |
| --- | --- | --- |
| SE | **PASS (review)** | AV primary 降格と Massive 条件付き採用が明確 |
| Programmer | **PASS (no code)** | Kotlin 変更なし |
| Data Integrity | **PASS** | UNKNOWN≠PASS；Search/meta≠Price；cross-ticker 安易 join 禁止；AV 削除禁止 |
| QA | **PASS** | PR #25 / entitlement review と整合；Real Backtest を開けない |

---

## 18. Merge advice

- Draft PR としてレビュー可
- **main へ自動 merge しない**
- 本文書は Massive client 実装許可の最小次工程指針であり、DailyPrice / Real Backtest 許可書ではない

---

## 19. References

- [`alpha-vantage-price-venue-semantics-gate.md`](alpha-vantage-price-venue-semantics-gate.md)
- [`venue-listing-identity-gate-review.md`](venue-listing-identity-gate-review.md)
- [`forward-security-price-join-gate-review.md`](forward-security-price-join-gate-review.md)
- [`price-source-entitlement-review.md`](price-source-entitlement-review.md)
- [`price-forward-archive-poc.md`](price-forward-archive-poc.md)
- [`data-contract.md`](data-contract.md)
- Official sources in §3
