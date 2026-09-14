# Price Historical knownAt / Currency / Entitlement Decision Review

**調査日 (UTC):** 2026-09-14  
**Repository:** `siyokugo-rgb/us-stock-investment-assistant`  
**Source of Truth:** GitHub `main`  
**Baseline SHA:** `10e7e57c9fa2784ea9aae73c277dd081ea1fd888`（照合済・一致）  
**Scope:** 文書調査・設計判断のみ。Provider client / API key / parser / DailyPrice mapper / Backtest / Strategy / DB / Android は対象外。  
**契約購入・有料申込:** なし。

この文書は実装採用の証明ではない。既存契約（[`data-contract.md`](data-contract.md) §2、[`price-data-poc.md`](price-data-poc.md)、[`corporate-action-split-poc.md`](corporate-action-split-poc.md)、[`feasibility-gate-review.md`](feasibility-gate-review.md)、[`stock-split-source-research.md`](stock-split-source-research.md)）を弱めない。

### 維持する不変条件

| 規則 | 扱い |
| --- | --- |
| raw OHLCV | 執行候補の基準。維持 |
| adjusted price → 約定禁止 | 維持 |
| currency 推測禁止（ticker/US/Eastern→USD 等） | 維持 |
| ticker → SecurityId 禁止 | 維持 |
| fetchedAt / ingestedAt → historical knownAt 禁止 | 維持 |
| split 無しの価格段差自動補正禁止 | 維持 |

---

## 0. Baseline 監査（契約・モデル）

| 対象 | 状態 |
| --- | --- |
| `docs/price-data-poc.md` | Alpha Vantage PoC **PARTIAL**。historical knownAt / currency / SecurityId mapping blocker |
| `docs/data-contract.md` §2 | `DailyPrice.currency` + `knownAt` 必須・独立 blocker。raw vs adjusted 分離 |
| `docs/corporate-action-split-poc.md` | CA PoC **PARTIAL**。`announcedAt` ≠ `knownAt` ≠ `fetchedAt` |
| `docs/feasibility-gate-review.md` | Real Backtest **NO-GO**。Price knownAt/currency Critical |
| `DailyPrice` / `SecurityId` / `SecurityIdentifier` | currency・knownAt 必須；ticker から SecurityId 生成禁止 |

**コード矛盾:** 本レビュー中に実コードの重大仕様矛盾は新規発見せず。既存どおり mapping は Fail-Closed。**コードは変更していない。**

---

## 1. knownAt 要求の用途別分解

一律「historical knownAt」を議論しない。

### A. Same-session decision

例: 2025-01-10 の日足 close を使い、同日 close で売買したと仮定。

| 論点 | 判定 |
| --- | --- |
| 危険 | EOD bar 完成前情報の利用＝look-ahead |
| 必要 evidence | Grade **A**（row/event 単位の publication）または、当日 close 時点でその bar が確定配信済みである一次根拠 |
| 今回調査の retail/free/安価 API | いずれも同日 close 約定を正当化する publication clock を持たない |
| **判定** | **原則 Fail-Closed（成立させない）** |

### B. Post-close decision

例: 2025-01-10 確定日足を、同日市場終了後に利用。

| 論点 | 判定 |
| --- | --- |
| 必要 | Provider の EOD finalization / cutoff の公式根拠 + revision 方針 |
| 候補 | Tiingo 製品ページが Equities/ETFs EOD を **~5:30pm EST**、exchange corrections を夕方〜**~8:00pm EST** までと記載（[Tiingo EOD product](https://www.tiingo.com/products/end-of-day-stock-price-data)） |
| 限界 | 「今日取得した historical API の過去行」が「当時の as-known」である証明にはならない。SLA は **forward の decisionAt 制約**候補であり、retrospective as-known の Grade A ではない |
| **判定** | 公式 cutoff 後 + 自己アーカイブなら **Grade B 候補（forward）**。retrospective bulk history は依然 **Grade C** |

### C. Next-session decision

例: 2025-01-10 確定日足を、2025-01-13 市場開始後に判断/執行。

| 論点 | 判定 |
| --- | --- |
| 「翌日だから安全」 | **禁止**。一次根拠必須 |
| 保守的成立条件（forward） | (1) 公式 EOD 配信保証が次セッション open より十分前、(2) その版を cutoff 後に自己アーカイブ、(3) revision が履歴を黙って書き換えない、または版管理がある |
| retrospective | historical row に publication timestamp / revision ledger が無い限り、現在の history を過去 decisionAt の as-known として使えない |
| **判定** | forward + documented SLA + self-archive → **条件付き Grade B 候補**。retrospective as-known → **現状 Grade C（不成立）** |

---

## 2. knownAt evidence Grade 定義

| Grade | 意味 |
| --- | --- |
| **A** | row/event 単位の historical publication timestamp あり |
| **B** | 公式 EOD publication SLA / cutoff / finalization policy により、decisionAt を保守的に制約すれば再現可能 |
| **C** | 現在取得できる historical row のみ。いつ利用可能だったか証明不能 |
| **D** | adjusted/revised history しかなく as-known 境界も不明 |

**独自判断での A/B 昇格は禁止。** 本レビューで Grade A と確定できた retail source は無い。

---

## 3. Source 調査サマリ（一次資料優先）

調査カテゴリ: 取引所公式系 / institutional vendor / retail vendor / Alpha Vantage / 既存 PoC 候補 / Security master・currency / Corporate Action。

### 3.1 Alpha Vantage（既存 Price PoC）

| 項目 | 公式根拠での所見 |
| --- | --- |
| raw daily OHLCV | `TIME_SERIES_DAILY` = raw as-traded（[AV docs](https://www.alphavantage.co/documentation/)） |
| volume | あり |
| currency | **日足レスポンスに無し**（既存 PoC 確認） |
| exchange / timezone | Meta `Time Zone`（例 US/Eastern）。currency 推測に使わない |
| historical depth | compact≈100；`full` は premium 寄り |
| delisted | 公式に広範 delisted universe entitlement を確認できず → **UNKNOWN / 実質 survivorship risk** |
| permanent ID | ticker 中心 |
| split / CA | 別 `SPLITS` 等。effective_date のみ → knownAt 不十分（既存 CA 研究） |
| publication timing | `Last Refreshed` は series 級。**per-row historical knownAt ではない** |
| revisions | 版履歴 API なし（確認範囲） |
| PIT history | **無し** → Grade **C** |
| pricing | Free 制限あり；Premium 公開ページ例: $49.99–$249.99/mo（rate）および高額帯（[premium](https://www.alphavantage.co/premium/)） |
| license | 既定は personal/non-commercial 寄り；commercial は別契約（[terms](https://www.alphavantage.co/terms_of_service/)） |
| caching / redistrib | ToS 制約。再配布は原則不可寄り |
| **技術判定** | raw 取得候補だが DailyPrice mapping **不可**（knownAt+currency+SecurityId） |
| **経済判定** | Free は運用資金と整合しやすいが entitlement 不足。Premium は 1万円運用に対し重い |

### 3.2 Polygon / Massive（旧 Polygon.io）

| 項目 | 所見 |
| --- | --- |
| raw | `adjusted=false` で split 未調整 OHLC（[Custom Bars docs](https://polygon.io/docs/stocks/get_v2_aggs_ticker__stocksticker__range__multiplier___timespan___from___to)） |
| currency | bar に無し。reference 別エンドポイント要確認 |
| identifier | ticker；内部 ticker id。FIGI/CUSIP は reference 側 |
| delisted | カバレッジ主張あり。free Basic は history **2y** |
| CA | Corporate Actions 含む（execution_date 中心 → PIT weak；既存研究） |
| knownAt | `results[].t` = aggregate **window start**。publication knownAt **ではない** → Grade **C** |
| pricing | Basic **$0**；Starter **$29**/mo；上位例 $79 / $199；Business 高額（[massive.com/pricing](https://massive.com/pricing)） |
| license | Individual = personal/non-professional；Business で commercial/redistrib |
| **技術** | raw 可。retrospective knownAt 不可 |
| **経済** | $29+/mo は 1万円運用に対し **TECHNICALLY_VALID_BUT_ECONOMICALLY_UNSUITABLE** 寄り |

### 3.3 Tiingo

| 項目 | 所見 |
| --- | --- |
| raw | EOD に raw OHLC + adj フィールド + splitFactor（製品/ KB） |
| currency | daily bar 自体に currency 無し。meta/`exchangeCode` 等は reference 側 → **ticker→USD 推測禁止のまま join 要** |
| update | 公式製品記載: Equities/ETFs **~5:30pm EST**；corrections ~**8:00pm EST** |
| revisions | KB: split/div 後は history 再取得推奨 → **過去行が後日変わり得る**。revision ledger なし → retrospective as-known **Grade C** |
| delisted / permaTicker | permaTicker 主張。広範 delisted universe の無料完全性は **UNKNOWN** |
| CA | splits/dividends API（exDate；announcement knownAt 弱） |
| pricing | $0 / **$30** / **$50**/mo internal；redistrib は別契約（製品ページ） |
| license | listed plans **Internal Use**；再配布は別契約 |
| knownAt grade | retrospective **C**；forward decisionAt≥cutoff+self-archive は **B-candidate（昇格確定ではない）** |
| **経済** | $30–50/mo ≈ 年 $360–600。運用資金 1万円に対し不釣り合い → **ECONOMICALLY_UNSUITABLE**（技術 FAIL にはしない） |

### 3.4 EODHD

| 項目 | 所見 |
| --- | --- |
| raw | EOD: `open/high/low/close` = raw；`adjusted_close` 別；volume は split adjusted（[EOD API](https://eodhd.com/financial-apis/api-for-historical-data-and-volumes)） |
| currency | **symbol list / fundamentals に Currency**（例 delisted list の `Currency`）。price row 自体には無し → Price→identity→currency evidence join が必要 |
| delisted | 公式に delisted=1 + EOD 履歴（[delisted docs](https://eodhd.com/financial-apis/delisted-stock-companies-data-2)）。**Survivorship 緩和候補** |
| symbol change | US symbol-change-history あり（ticker 履歴の補助）。**それでも ticker 単独 join 禁止** |
| knownAt | publication timestamp / revision ledger **なし**（確認範囲）→ Grade **C** |
| pricing | Free 制限；EOD All World **$19.99**/mo；All-in-one **$99.99**/mo（[pricing](https://eodhd.com/pricing)）。Commercial Internal **$399**/mo 等（[commercial](https://eodhd.com/commercial-pricing)） |
| license | 標準パッケージは **personal**；commercial は別。再配布禁止寄り（[commercial vs personal](https://eodhd.com/financial-apis/commercial-vs-personal-license-use)） |
| **経済** | 個人 $19.99/mo でも年≈$240。1万円運用に対し重い。製品配布なら commercial で更に高額 |

### 3.5 Finnhub

| 項目 | 所見 |
| --- | --- |
| free | quote / profile2 等。historical candles は上位 |
| knownAt | candle に publication PIT なし → Grade **C** |
| cost | All-In-One 等は高額帯（公開ページで数千 USD/mo 級の記載あり）→ 1万円運用と不整合 |
| **技術/経済** | 広範 historical entitlement は **C / ECONOMICALLY_UNSUITABLE** |

### 3.6 Nasdaq Data Link WIKI

| 項目 | 所見 |
| --- | --- |
| 状態 | **Discontinued (~2018)**。現行 live source ではない |
| 判定 | 採用不可 |

### 3.7 取引所公式系（価格）

| Source | 所見 |
| --- | --- |
| NYSE/Nasdaq official tapes / Daily List 等 | 構造化・PIT に近いが **有料ライセンス**。CUSIP 再配布制約あり。1万円運用向けではない |
| 無料 HTML alerts | 価格時系列 entitlement ではない |

### 3.8 Institutional PIT（Bloomberg / LSEG / FactSet / CRSP）

| 項目 | 所見 |
| --- | --- |
| knownAt / as-of | 業界標準の point-in-time / revision 管理が期待される領域 |
| cost | 端末・Data License は通常 **年額数万〜数十万 USD 級**（公開カタログは見積ベース；正確額は sales） |
| 判定 | retrospective 安全 backtest の技術経路としては有望。経済は **UNSUITABLE** for ¥10k |

### 3.9 Security master / currency

| Source | 役割 | 限界 |
| --- | --- | --- |
| **OpenFIGI** | FIGI mapping（無料）。currency は **filter**；取引通貨の authoritative history ではない。proprietary IDs は入力可・**返却しない**ことが多い（[OpenFIGI docs](https://www.openfigi.com/api/documentation) / FAQ） | Security master 単独としては不完全。ticker→SecurityId 禁止は維持 |
| **EODHD symbol list / fundamentals** | `Currency`, Exchange, ISIN（欠損あり）, IsDelisted | validity period / PIT currency 変更履歴は **不完全/UNKNOWN**。join に provenance 必須 |
| **Tiingo meta / Massive reference** | exchange 等 | trading currency の historical evidence は source ごとに要監査。推測禁止 |
| **Exchange listed company files** | 公式だが機械化・歴史 PIT は製品依存 | 有料が多い |

**結論:** Price とは別に **Security master entitlement** を独立 blocker として正式化する（本実装はしない）。

### 3.10 Corporate Action

既存 [`stock-split-source-research.md`](stock-split-source-research.md) / CA PoC を継承。

| 候補 | knownAt | コスト |
| --- | --- | --- |
| SEC EDGAR 8-K 等 | acceptance は disclosure lower-bound；構造化 CA ではない | Free |
| Nasdaq alerts / Daily List / NYSE MEF | publish/effective 改善は有料製品側 | Free HTML は不完全；製品は有料 |
| Tiingo / Massive / AV / EODHD splits | ex/effective 中心。Grade A announcement PIT なし | 低〜中 |
| LSEG / Bloomberg CA | announcement フィールド等で相対的に強い | 高額 |

**無料で PIT-safe な CA 完成 entitlement は依然無し。**

---

## 4. Currency 解決経路

`DailyPrice.currency` は必須。推測禁止。

| 経路 | 可否 |
| --- | --- |
| price response 自身に currency | AV / Polygon bars / Tiingo daily：**原則無し** |
| security/reference の trading currency | EODHD symbol list `Currency` 等は **候補 evidence**。ただし as-of と SecurityId 整合が必要 |
| historical currency 変更 | 今回確認した安価 API に完全な currency change PIT ledger は見当たらず → **UNKNOWN / blocker 残** |
| ADR / dual listing / multi-currency | 同一 ticker 文字列では解決不能。FIGI/share-class + exchange + currency evidence の複合 join が必要 |
| ticker→USD | **禁止（契約維持）** |

**別 source の場合の join（必須要素）:**

```text
Price record
  → external identifier (FIGI / exchange+symbol+MIC / vendor perma id) + validity period
  → SecurityId（内部；ticker から生成しない）
  → currency evidence（reference row）+ provenance + PIT(knownAt_currency <= decisionAt)
```

currency 未解決と knownAt 未解決は **独立 blocker**（data-contract §2.1.1）。

---

## 5. raw / adjusted / correction

| Source | raw/as-traded | split adj | dividend adj | TR | retrospective corrections |
| --- | --- | --- | --- | --- | --- |
| AV Daily | Yes（別 API が adjusted） | 別 | 別 | N/A | 版履歴なし → 現在取得 history を as-known にできない |
| Polygon `adjusted=false` | Yes（split 未調整） | default true | UNKNOWN（docs は splits 中心） | N/A | bar `t` ≠ publication；correction ledger なし |
| Tiingo | Yes + adj fields | Yes | Yes（adj） | 近似可だが執行禁止 | split/div 後に history 再取得推奨 → **書き換えリスク明示** |
| EODHD | OHLC raw；adj_close 別；volume split-adj | adj_close | adj_close | N/A | revision ledger なし → Grade C |

**監査結論:** revision 履歴が無い source では、**現在取得した history を過去 decisionAt の as-known data としてそのまま利用してはならない。**

---

## 6. Delisted / Survivorship

| Source | delisted price history | 広範 Universe Backtest |
| --- | --- | --- |
| AV free/premium（確認範囲） | 不十分 / UNKNOWN | **Survivorship Bias blocker 残** |
| Massive Basic | 2y + active bias risk | blocker 残 |
| EODHD | 公式 delisted 経路あり | **緩和候補**（それでも identity/PIT/CA 不足） |
| Institutional / CRSP | 研究品質の longitudinal ID | 高額 |

現役銘柄のみ取得可能な source は、広範 Universe Backtest では **Survivorship Bias を Critical/High blocker として残す**。

---

## 7. Security master entitlement（独立 blocker）

**正式化:** 「Security master entitlement」を Price とは独立した blocker とする。

必要能力:

- Security identity（内部 SecurityId への安全な外部 ID 紐付け）
- ticker history + validity window
- exchange / listing venue
- trading currency evidence
- listing / delisting
- permanent identifier（FIGI 等；CUSIP は再配布制約に注意）

OpenFIGI は mapping 補助であり、単独 master ではない。

---

## 8. 1万円運用との費用整合

想定運用資金: **¥10,000**。

| 年額オーダー（公開ページ） | 対 1万円 |
| --- | --- |
| Free（AV/Massive Basic/Tiingo $0） | 整合しやすいが **技術不足** |
| ~$20–50/mo（EODHD/Tiingo/Massive Starter） | 年 $240–600 ≒ 運用資金の数倍〜 → **ECONOMICALLY_UNSUITABLE** |
| ~$200+/mo / commercial $399+ | 明らかに不釣り合い |
| Institutional | 技術経路になり得るが経済は論外 |

**重要:** 高額でも「技術 FAIL」にはしない。分離ラベル:

- `TECHNICALLY_PROMISING`
- `TECHNICALLY_VALID_BUT_ECONOMICALLY_UNSUITABLE`
- `TECHNICALLY_INSUFFICIENT`

---

## 9. Backtest 方式への影響

| Option | 内容 | 必要 knownAt | 今回判定 |
| --- | --- | --- | --- |
| **A same-close** | EOD close を同日 close 約定 | Grade A 級の当日確定配信証明 | **Fail-Closed / 不成立**（一次根拠なし） |
| **B next-open** | 前日確定足 → 翌営業日 open 執行 | 前日 bar が next open 前に利用可能だった根拠（SLA+archive または Grade A） | forward 条件付き検討可；retrospective **不成立（Grade C）** |
| **C delayed / next-session+** | さらに遅延（例: T+1 close 後、または cutoff+buffer 後） | SLA を超える保守バッファ + self-archive | forward の保守運用候補。retrospective はなお Grade C |

EOD daily bar + same-day close 約定は、**一次根拠無しに look-ahead 無しと主張してはならない。**

---

## 10. Minimum Backtestable Slice 再評価

対象: 単一 Security / price-only / next-session execution。

| 判定 | 意味 | 本レビュー |
| --- | --- | --- |
| A | 契約なし無料で成立 | **不可**（knownAt C + currency 未解決 + identity） |
| B | 安価契約で成立 | **retrospective 不可**。forward-only self-archive + currency evidence + 明示 SecurityId 手入力 + CA 無し限定なら **条件付き検討**だが Real Backtest ではない |
| C | 高額/institutional なら成立 | retrospective PIT にはこれが本筋 |
| D | 現状不成立 | **安全な retrospective Minimum Slice は D** |

**再判定: D（現状、契約なし／安価 API の retrospective as-known slice は不成立）。**  
付記: forward-only 実験は B 経路の議論余地があるが、本プロジェクトの Real Backtest 要件を満たさない。

---

## 11. Source decision matrix

不明は UNKNOWN。推測値禁止。

| Source | raw price | currency | identifier | delisted | CA | knownAt grade | history | license | cost | 技術判定 | 経済判定 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| Alpha Vantage Daily | YES (raw) | NO in bar | ticker | UNKNOWN | weak (eff date) | C | compact free; full premium | personal default; commercial separate | Free / ~$50–250+/mo | INSUFFICIENT for DailyPrice | Free OK; Premium heavy vs ¥10k |
| Massive/Polygon aggs | YES if adjusted=false | NO in bar | ticker (+ ref) | PARTIAL | weak (exec date) | C | Basic 2y; paid deeper | individual non-pro; Business for commercial | $0 / $29 / $79 / $199+ | INSUFFICIENT knownAt | $29+ UNSUITABLE vs ¥10k |
| Tiingo EOD | YES + adj fields | NO in bar (meta/ref) | ticker + permaTicker | UNKNOWN | weak/beta splits | C (B-candidate forward SLA only) | 30y+ claim | Internal; redistrib sales | $0 / $30 / $50; redistrib higher | PROMISING raw; insufficient retrospective PIT | $30+ UNSUITABLE |
| EODHD EOD | YES OHLC raw | on symbol list / fund. | ticker + ISIN? | YES (documented) | splits/div endpoints | C | 30y+ claim; free 1y | personal vs commercial | $0 / $19.99 / $99.99; commercial $399+ | PROMISING raw+delisted; insufficient knownAt | paid UNSUITABLE; commercial worse |
| Finnhub | PARTIAL (hist paid) | profile 系 | ticker | UNKNOWN | UNKNOWN | C | plan-dependent | plan-dependent | free limited; hist expensive | INSUFFICIENT / costly | UNSUITABLE |
| Nasdaq WIKI | N/A | N/A | N/A | N/A | N/A | N/A | discontinued | N/A | N/A | REJECT | N/A |
| OpenFIGI | NO (not price) | filter only | FIGI mapping | includeUnlisted filter | NO | N/A | N/A | free FIGI reuse | Free | master assist only | OK |
| SEC EDGAR | NO | NO | CIK (issuer) | N/A | narrative CA | lower-bound disclosure only | archive | public reuse | Free | CA evidence partial | OK |
| Exchange CA products | NO/partial | N/A | CUSIP+ | N/A | strong structured | B-ish if publish archived | paid depth | licensed | HIGH | CA path | UNSUITABLE |
| Bloomberg/LSEG/FactSet/CRSP | YES (entitled) | YES typical | strong IDs | YES typical | strong | A/B institutional typical | deep | strict | VERY HIGH | TECHNICALLY_PROMISING for PIT | UNSUITABLE vs ¥10k |

---

## 12. 推奨構成（契約・購入はしない）

| 層 | 推奨（調査時点） | 代替 | 備考 |
| --- | --- | --- | --- |
| **Price** | EODHD または Tiingo（raw OHLC 明示） | Massive `adjusted=false` | AV は PoC 継続可だが mapping 不可のまま |
| **Currency** | Price と同一 vendor の symbol/reference **explicit Currency**（例 EODHD list）を evidence として join | OpenFIGI currency filter は **不十分**（filter ≠ trading currency history） | USD 推測禁止 |
| **Security master** | OpenFIGI（FIGI）+ vendor permanent id + 手管理の validity | 将来 exchange master / paid | **独立 blocker** |
| **CA** | SEC EDGAR +（将来）有料 exchange/vendor CA | Tiingo/Massive splits は補助のみ | PIT-safe free CA は未解決 |

Join 必須: external identifier + validity period + provenance + PIT 条件。**ticker 文字列 join 禁止。**

---

## 13. 最終 decision

### 総合判定: **D**

**現段階では source 要件（retrospective historical knownAt を含む安全な Minimum Backtestable Slice）を満たせない。**

補足ラベル:

| 経路 | ラベル |
| --- | --- |
| 無料組合せで安全 retrospective slice | **不可（≠A）** |
| 安価有料 + forward self-archive only | 議論余地あるが Real Backtest 非該当；費用は **ECONOMICALLY_UNSUITABLE** |
| institutional PIT | 技術的本筋 → 総合では **C 経路**だが本レビューの「現段階で満たせるか」は **D** |
| Real Backtest | **NO-GO**（維持） |

### Critical blockers

1. historical knownAt（retrospective Grade A/B 不在）
2. currency evidence の PIT-safe join 未確立
3. SecurityId 安全 join（Security master entitlement）
4. CA PIT（split 無し自動補正禁止のまま接続不足）

### High blockers

1. Survivorship / delisted（AV 等）
2. session semantics（regular close 証明不足）
3. revision/correction 無版管理
4. license（personal vs commercial / redistrib）
5. 費用 vs ¥10k（有料経路）

---

## 14. SE / Data Integrity / QA

| Lens | 判定 |
| --- | --- |
| **SE** | **PARTIAL / NO-GO**。設計判断として D。実装に進まない判断は正しい |
| **Data Integrity** | 契約維持。Grade 独断昇格なし。技術と経済を分離 |
| **QA** | 文書レビューのみ。build/test **未実行（文書のみ）** |

---

## 15. 次の最小作業（実装しない／契約しない）

1. 本レビューを Draft PR として固定し、Real Backtest NO-GO を再確認する。  
2. （任意・別 PR）forward-only research の **設計ノート**のみ：SLA cutoff + self-archive knownAt の定義案（実装禁止のまま）。  
3. Security Master Acceptance Criteria は [`security-master-acceptance-criteria.md`](security-master-acceptance-criteria.md) で正式化（本項目の受け入れ基準定義）。  
4. CA は既存 PARTIAL のまま；無料 PIT-safe CA が無い事実を維持。  
5. ¥10k 制約下では institutional を「技術オプション」として記録し、採用判断は経済レビューに分離。

---

## 16. Primary sources consulted（2026-09-14）

- https://www.alphavantage.co/documentation/
- https://www.alphavantage.co/premium/
- https://www.alphavantage.co/terms_of_service/
- https://polygon.io/docs/stocks/get_v2_aggs_ticker__stocksticker__range__multiplier___timespan___from___to
- https://massive.com/pricing
- https://massive.com/stocks
- https://www.tiingo.com/products/end-of-day-stock-price-data
- https://www.tiingo.com/kb/article/the-fastest-method-to-ingest-tiingo-end-of-day-stock-api-data/
- https://eodhd.com/financial-apis/api-for-historical-data-and-volumes
- https://eodhd.com/financial-apis/delisted-stock-companies-data-2
- https://eodhd.com/pricing
- https://eodhd.com/commercial-pricing
- https://eodhd.com/financial-apis/commercial-vs-personal-license-use
- https://www.openfigi.com/api/documentation
- https://www.openfigi.com/about/faq
- 既存 repo docs: `price-data-poc.md` / `data-contract.md` / `feasibility-gate-review.md` / `corporate-action-split-poc.md` / `stock-split-source-research.md`

---

## 17. Build / test

**文書のみのため未実行。**

## 18. Real Backtest GO / NO-GO

**NO-GO**
