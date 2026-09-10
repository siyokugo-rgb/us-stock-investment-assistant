import React from 'react';

// Lightweight SVG line chart for a series of { date, close } points.
export default function PriceChart({ series, height = 220 }) {
  if (!series || series.length < 2) {
    return <div className="chart-empty">No chart data</div>;
  }

  const width = 720;
  const padding = { top: 16, right: 16, bottom: 24, left: 48 };
  const innerW = width - padding.left - padding.right;
  const innerH = height - padding.top - padding.bottom;

  const closes = series.map((p) => p.close);
  const min = Math.min(...closes);
  const max = Math.max(...closes);
  const range = max - min || 1;

  const x = (i) => padding.left + (i / (series.length - 1)) * innerW;
  const y = (v) => padding.top + innerH - ((v - min) / range) * innerH;

  const linePath = series
    .map((p, i) => `${i === 0 ? 'M' : 'L'} ${x(i).toFixed(2)} ${y(p.close).toFixed(2)}`)
    .join(' ');

  const areaPath =
    `${linePath} L ${x(series.length - 1).toFixed(2)} ${padding.top + innerH} ` +
    `L ${x(0).toFixed(2)} ${padding.top + innerH} Z`;

  const rising = closes[closes.length - 1] >= closes[0];
  const stroke = rising ? '#16c784' : '#ea3943';
  const fill = rising ? 'rgba(22,199,132,0.12)' : 'rgba(234,57,67,0.12)';

  const ticks = 4;
  const gridLines = Array.from({ length: ticks + 1 }, (_, i) => {
    const v = min + (range * i) / ticks;
    return { v, yy: y(v) };
  });

  const labelEvery = Math.ceil(series.length / 6);

  return (
    <svg className="chart" viewBox={`0 0 ${width} ${height}`} preserveAspectRatio="xMidYMid meet">
      {gridLines.map((g, i) => (
        <g key={i}>
          <line x1={padding.left} x2={width - padding.right} y1={g.yy} y2={g.yy} className="grid" />
          <text x={padding.left - 8} y={g.yy + 4} className="axis-label" textAnchor="end">
            {g.v.toFixed(0)}
          </text>
        </g>
      ))}
      {series.map((p, i) =>
        i % labelEvery === 0 ? (
          <text key={p.date} x={x(i)} y={height - 6} className="axis-label" textAnchor="middle">
            {p.date.slice(5)}
          </text>
        ) : null
      )}
      <path d={areaPath} fill={fill} />
      <path d={linePath} fill="none" stroke={stroke} strokeWidth="2" />
    </svg>
  );
}
