import express from 'express';
import cors from 'cors';
import { getStock, listPopularSymbols } from './stockService.js';
import { analyzeCloses } from './analysis.js';

const app = express();
const PORT = process.env.PORT || 4000;

app.use(cors());
app.use(express.json());

app.get('/api/health', (_req, res) => {
  res.json({ status: 'ok', service: 'us-stock-investment-assistant', time: new Date().toISOString() });
});

app.get('/api/symbols', (_req, res) => {
  res.json({ symbols: listPopularSymbols() });
});

app.get('/api/stocks/:symbol', async (req, res) => {
  try {
    const data = await getStock(req.params.symbol);
    const closes = data.series.map((p) => p.close);
    const analysis = analyzeCloses(closes);

    const price = analysis.price;
    const prevClose = closes.length > 1 ? closes[closes.length - 2] : price;
    const change = price - prevClose;
    const changePercent = prevClose ? (change / prevClose) * 100 : 0;

    res.json({
      symbol: data.symbol,
      name: data.name,
      currency: data.currency,
      source: data.source,
      price,
      change,
      changePercent,
      indicators: {
        sma20: analysis.sma20,
        sma50: analysis.sma50,
        rsi: analysis.rsi,
      },
      recommendation: analysis.recommendation,
      series: data.series,
    });
  } catch (err) {
    res.status(500).json({ error: err.message || 'Failed to load stock' });
  }
});

// Analyze a full watchlist in one request.
app.post('/api/analyze', async (req, res) => {
  try {
    const symbols = Array.isArray(req.body?.symbols) ? req.body.symbols : [];
    if (symbols.length === 0) {
      return res.status(400).json({ error: 'Provide a non-empty "symbols" array.' });
    }
    const results = await Promise.all(
      symbols.slice(0, 20).map(async (sym) => {
        const data = await getStock(sym);
        const closes = data.series.map((p) => p.close);
        const analysis = analyzeCloses(closes);
        const prevClose = closes.length > 1 ? closes[closes.length - 2] : analysis.price;
        return {
          symbol: data.symbol,
          name: data.name,
          source: data.source,
          price: analysis.price,
          changePercent: prevClose ? ((analysis.price - prevClose) / prevClose) * 100 : 0,
          rsi: analysis.rsi,
          recommendation: analysis.recommendation,
        };
      })
    );
    res.json({ results });
  } catch (err) {
    res.status(500).json({ error: err.message || 'Failed to analyze watchlist' });
  }
});

app.listen(PORT, () => {
  console.log(`[server] US Stock Investment Assistant API listening on http://localhost:${PORT}`);
});
