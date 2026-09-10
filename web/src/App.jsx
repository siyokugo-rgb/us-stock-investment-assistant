import React, { useEffect, useMemo, useState } from 'react';
import { getStock, analyzeWatchlist } from './api.js';
import PriceChart from './components/PriceChart.jsx';

const DEFAULT_WATCHLIST = ['AAPL', 'MSFT', 'NVDA', 'TSLA', 'AMZN'];

function actionClass(action) {
  if (action === 'BUY') return 'badge buy';
  if (action === 'SELL') return 'badge sell';
  return 'badge hold';
}

function fmt(v, digits = 2) {
  return v == null ? '—' : Number(v).toFixed(digits);
}

export default function App() {
  const [watchlist, setWatchlist] = useState(DEFAULT_WATCHLIST);
  const [rows, setRows] = useState([]);
  const [loadingList, setLoadingList] = useState(true);
  const [selected, setSelected] = useState('AAPL');
  const [detail, setDetail] = useState(null);
  const [loadingDetail, setLoadingDetail] = useState(true);
  const [input, setInput] = useState('');
  const [error, setError] = useState('');

  async function refreshWatchlist(list) {
    setLoadingList(true);
    try {
      const { results } = await analyzeWatchlist(list);
      setRows(results);
    } catch (e) {
      setError(e.message);
    } finally {
      setLoadingList(false);
    }
  }

  async function loadDetail(symbol) {
    setLoadingDetail(true);
    setError('');
    try {
      const data = await getStock(symbol);
      setDetail(data);
    } catch (e) {
      setError(e.message);
    } finally {
      setLoadingDetail(false);
    }
  }

  useEffect(() => {
    refreshWatchlist(watchlist);
  }, []);

  useEffect(() => {
    loadDetail(selected);
  }, [selected]);

  function handleAdd(e) {
    e.preventDefault();
    const sym = input.toUpperCase().trim();
    if (!sym) return;
    if (!watchlist.includes(sym)) {
      const next = [...watchlist, sym];
      setWatchlist(next);
      refreshWatchlist(next);
    }
    setSelected(sym);
    setInput('');
  }

  function handleRemove(sym) {
    const next = watchlist.filter((s) => s !== sym);
    setWatchlist(next);
    refreshWatchlist(next);
    if (selected === sym && next.length) setSelected(next[0]);
  }

  const changeColor = (v) => (v >= 0 ? 'pos' : 'neg');

  const rec = detail?.recommendation;

  return (
    <div className="app">
      <header className="topbar">
        <div className="brand">
          <span className="logo">▲</span>
          <div>
            <h1>US Stock Investment Assistant</h1>
            <p>Live quotes, technical indicators, and rule-based guidance</p>
          </div>
        </div>
        <form className="search" onSubmit={handleAdd}>
          <input
            value={input}
            onChange={(e) => setInput(e.target.value)}
            placeholder="Add symbol (e.g. GOOGL)"
            aria-label="Add symbol"
          />
          <button type="submit">Add</button>
        </form>
      </header>

      {error && <div className="error-banner">{error}</div>}

      <main className="layout">
        <section className="panel watchlist">
          <div className="panel-head">
            <h2>Watchlist</h2>
            <button className="ghost" onClick={() => refreshWatchlist(watchlist)}>
              Refresh
            </button>
          </div>
          {loadingList ? (
            <div className="skeleton-list">Loading…</div>
          ) : (
            <ul className="ticker-list">
              {rows.map((r) => (
                <li
                  key={r.symbol}
                  className={`ticker ${selected === r.symbol ? 'active' : ''}`}
                  onClick={() => setSelected(r.symbol)}
                >
                  <div className="ticker-main">
                    <span className="sym">{r.symbol}</span>
                    <span className="name">{r.name}</span>
                  </div>
                  <div className="ticker-side">
                    <span className="price">${fmt(r.price)}</span>
                    <span className={`chg ${changeColor(r.changePercent)}`}>
                      {r.changePercent >= 0 ? '+' : ''}
                      {fmt(r.changePercent)}%
                    </span>
                  </div>
                  <span className={actionClass(r.recommendation.action)}>{r.recommendation.action}</span>
                  <button
                    className="remove"
                    onClick={(e) => {
                      e.stopPropagation();
                      handleRemove(r.symbol);
                    }}
                    aria-label={`Remove ${r.symbol}`}
                  >
                    ×
                  </button>
                </li>
              ))}
            </ul>
          )}
        </section>

        <section className="panel detail">
          {loadingDetail || !detail ? (
            <div className="skeleton-list">Loading details…</div>
          ) : (
            <>
              <div className="detail-head">
                <div>
                  <h2>
                    {detail.symbol} <span className="company">{detail.name}</span>
                  </h2>
                  <div className="price-row">
                    <span className="big-price">${fmt(detail.price)}</span>
                    <span className={`chg ${changeColor(detail.changePercent)}`}>
                      {detail.change >= 0 ? '+' : ''}
                      {fmt(detail.change)} ({detail.changePercent >= 0 ? '+' : ''}
                      {fmt(detail.changePercent)}%)
                    </span>
                    <span className="source-tag" title="Data source">
                      {detail.source === 'yahoo' ? 'live' : 'demo data'}
                    </span>
                  </div>
                </div>
                <div className={`rec-card ${rec.action.toLowerCase()}`}>
                  <span className="rec-label">Recommendation</span>
                  <span className={actionClass(rec.action)}>{rec.action}</span>
                  <span className="rec-conf">{rec.confidence}% confidence</span>
                </div>
              </div>

              <PriceChart series={detail.series} />

              <div className="indicators">
                <div className="indicator">
                  <span className="k">SMA 20</span>
                  <span className="v">${fmt(detail.indicators.sma20)}</span>
                </div>
                <div className="indicator">
                  <span className="k">SMA 50</span>
                  <span className="v">${fmt(detail.indicators.sma50)}</span>
                </div>
                <div className="indicator">
                  <span className="k">RSI 14</span>
                  <span className="v">{fmt(detail.indicators.rsi, 1)}</span>
                </div>
              </div>

              <div className="signals">
                <h3>Why {rec.action}?</h3>
                <ul>
                  {rec.signals.map((s, i) => (
                    <li key={i}>{s}</li>
                  ))}
                </ul>
                <p className="disclaimer">
                  For educational purposes only. This is not financial advice.
                </p>
              </div>
            </>
          )}
        </section>
      </main>
    </div>
  );
}
