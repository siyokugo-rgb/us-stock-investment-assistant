// Stock data service.
// Primary source: Yahoo Finance public chart API (no API key required).
// Fallback: deterministic synthetic series so the app always works offline.

const YAHOO_BASE = 'https://query1.finance.yahoo.com/v8/finance/chart';

const COMPANY_NAMES = {
  AAPL: 'Apple Inc.',
  MSFT: 'Microsoft Corporation',
  GOOGL: 'Alphabet Inc.',
  AMZN: 'Amazon.com, Inc.',
  NVDA: 'NVIDIA Corporation',
  TSLA: 'Tesla, Inc.',
  META: 'Meta Platforms, Inc.',
  NFLX: 'Netflix, Inc.',
  JPM: 'JPMorgan Chase & Co.',
  V: 'Visa Inc.',
};

// Deterministic pseudo-random generator so mock data is stable per symbol.
function seededRandom(seed) {
  let s = seed % 2147483647;
  if (s <= 0) s += 2147483646;
  return () => {
    s = (s * 16807) % 2147483647;
    return (s - 1) / 2147483646;
  };
}

function hashSymbol(symbol) {
  let h = 0;
  for (let i = 0; i < symbol.length; i++) {
    h = (h * 31 + symbol.charCodeAt(i)) & 0xffffffff;
  }
  return Math.abs(h);
}

function buildMockSeries(symbol, days = 180) {
  const rand = seededRandom(hashSymbol(symbol) + 1);
  let price = 50 + (hashSymbol(symbol) % 250);
  const series = [];
  const now = Date.now();
  for (let i = days; i >= 0; i--) {
    const drift = (rand() - 0.48) * price * 0.03;
    price = Math.max(1, price + drift);
    series.push({
      date: new Date(now - i * 86400000).toISOString().slice(0, 10),
      close: Number(price.toFixed(2)),
    });
  }
  return series;
}

async function fetchFromYahoo(symbol, range = '6mo') {
  const url = `${YAHOO_BASE}/${encodeURIComponent(symbol)}?range=${range}&interval=1d`;
  const controller = new AbortController();
  const timeout = setTimeout(() => controller.abort(), 8000);
  try {
    const res = await fetch(url, {
      signal: controller.signal,
      headers: { 'User-Agent': 'Mozilla/5.0 (compatible; usia/1.0)' },
    });
    if (!res.ok) throw new Error(`Yahoo responded ${res.status}`);
    const json = await res.json();
    const result = json?.chart?.result?.[0];
    if (!result) throw new Error('No chart result');

    const timestamps = result.timestamp || [];
    const closes = result.indicators?.quote?.[0]?.close || [];
    const meta = result.meta || {};

    const series = [];
    for (let i = 0; i < timestamps.length; i++) {
      const c = closes[i];
      if (c == null) continue;
      series.push({
        date: new Date(timestamps[i] * 1000).toISOString().slice(0, 10),
        close: Number(c.toFixed(2)),
      });
    }
    if (series.length === 0) throw new Error('Empty series');

    return {
      series,
      name: meta.longName || meta.shortName || COMPANY_NAMES[symbol] || symbol,
      currency: meta.currency || 'USD',
      source: 'yahoo',
    };
  } finally {
    clearTimeout(timeout);
  }
}

export async function getStock(symbolRaw) {
  const symbol = String(symbolRaw || '').toUpperCase().trim();
  if (!symbol) throw new Error('Symbol is required');

  try {
    const { series, name, currency, source } = await fetchFromYahoo(symbol);
    return { symbol, name, currency, source, series };
  } catch (err) {
    // Graceful fallback keeps the demo functional without network access.
    return {
      symbol,
      name: COMPANY_NAMES[symbol] || `${symbol} (mock)`,
      currency: 'USD',
      source: 'mock',
      series: buildMockSeries(symbol),
    };
  }
}

export function listPopularSymbols() {
  return Object.entries(COMPANY_NAMES).map(([symbol, name]) => ({ symbol, name }));
}
