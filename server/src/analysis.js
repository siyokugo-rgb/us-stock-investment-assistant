// Technical analysis helpers for the investment assistant.
// All functions operate on an array of daily closing prices (oldest -> newest).

export function simpleMovingAverage(closes, period) {
  if (closes.length < period) return null;
  const slice = closes.slice(closes.length - period);
  const sum = slice.reduce((acc, v) => acc + v, 0);
  return sum / period;
}

// Wilder's RSI over the given period.
export function relativeStrengthIndex(closes, period = 14) {
  if (closes.length < period + 1) return null;

  let gains = 0;
  let losses = 0;
  for (let i = closes.length - period; i < closes.length; i++) {
    const change = closes[i] - closes[i - 1];
    if (change >= 0) gains += change;
    else losses -= change;
  }

  const avgGain = gains / period;
  const avgLoss = losses / period;
  if (avgLoss === 0) return 100;
  const rs = avgGain / avgLoss;
  return 100 - 100 / (1 + rs);
}

// Produce a rule-based recommendation from the computed indicators.
// This is educational guidance, not financial advice.
export function buildRecommendation({ price, sma20, sma50, rsi }) {
  const signals = [];
  let score = 0;

  if (sma20 != null && sma50 != null) {
    if (sma20 > sma50) {
      score += 1;
      signals.push('20-day SMA is above the 50-day SMA (bullish trend).');
    } else {
      score -= 1;
      signals.push('20-day SMA is below the 50-day SMA (bearish trend).');
    }
  }

  if (sma20 != null) {
    if (price > sma20) {
      score += 1;
      signals.push('Price is trading above its 20-day average (momentum up).');
    } else {
      score -= 1;
      signals.push('Price is trading below its 20-day average (momentum down).');
    }
  }

  if (rsi != null) {
    if (rsi < 30) {
      score += 1;
      signals.push(`RSI is ${rsi.toFixed(1)} — oversold, potential rebound.`);
    } else if (rsi > 70) {
      score -= 1;
      signals.push(`RSI is ${rsi.toFixed(1)} — overbought, potential pullback.`);
    } else {
      signals.push(`RSI is ${rsi.toFixed(1)} — neutral momentum.`);
    }
  }

  let action = 'HOLD';
  if (score >= 2) action = 'BUY';
  else if (score <= -2) action = 'SELL';

  const confidence = Math.min(100, 50 + Math.abs(score) * 15);

  return { action, score, confidence, signals };
}

export function analyzeCloses(closes) {
  const price = closes[closes.length - 1];
  const sma20 = simpleMovingAverage(closes, 20);
  const sma50 = simpleMovingAverage(closes, 50);
  const rsi = relativeStrengthIndex(closes, 14);
  const recommendation = buildRecommendation({ price, sma20, sma50, rsi });
  return { price, sma20, sma50, rsi, recommendation };
}
