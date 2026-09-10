# US Stock Investment Assistant

A full-stack web app that fetches US stock data, computes technical indicators
(SMA 20/50, RSI 14), and gives rule-based **BUY / HOLD / SELL** guidance with an
explanation of the signals behind each recommendation.

> For educational purposes only. This is **not** financial advice.

## Architecture

| Layer   | Stack                          | Port |
| ------- | ------------------------------ | ---- |
| API     | Node.js + Express (ESM)        | 4000 |
| Web     | React + Vite                   | 5173 |

- **Data source:** Yahoo Finance public chart API (no API key required).
- **Offline fallback:** if the network is unavailable, the API serves a stable
  synthetic series so the app always works for demos/tests.

## Getting started

```bash
npm install        # installs root + workspaces
npm run dev        # runs API (4000) and web (5173) together
```

Then open http://localhost:5173.

You can also run each side individually:

```bash
npm run dev:server
npm run dev:web
```

## API endpoints

| Method | Path                  | Description                                  |
| ------ | --------------------- | -------------------------------------------- |
| GET    | `/api/health`         | Service health check                         |
| GET    | `/api/symbols`        | Popular symbols with company names           |
| GET    | `/api/stocks/:symbol` | Quote, indicators, recommendation, series    |
| POST   | `/api/analyze`        | Batch-analyze a `{ "symbols": [...] }` list  |

## Cloud Agent environment

The dev environment is defined in [`.cursor/environment.json`](.cursor/environment.json):

- `install`: `npm install`
- `terminals`: `api` and `web` dev servers
- `ports`: `4000`, `5173`

## Project layout

```
server/   Express API (stock service + technical analysis)
web/      React + Vite front end
```
