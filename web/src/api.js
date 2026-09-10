const BASE = '/api';

async function json(url, options) {
  const res = await fetch(url, options);
  if (!res.ok) {
    const body = await res.json().catch(() => ({}));
    throw new Error(body.error || `Request failed: ${res.status}`);
  }
  return res.json();
}

export function getSymbols() {
  return json(`${BASE}/symbols`);
}

export function getStock(symbol) {
  return json(`${BASE}/stocks/${encodeURIComponent(symbol)}`);
}

export function analyzeWatchlist(symbols) {
  return json(`${BASE}/analyze`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ symbols }),
  });
}
