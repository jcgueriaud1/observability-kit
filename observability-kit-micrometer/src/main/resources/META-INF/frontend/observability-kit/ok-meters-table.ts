// Copyright 2000-2026 Vaadin Ltd.
// Licensed under the Vaadin Commercial License and Service Terms.

import { LitElement, css, html, nothing } from 'lit';
import type { TemplateResult } from 'lit';

import { formatMeterValue, formatTags, meterKey, trendValue } from './format';
import type { Meter } from './model';
import { tokens } from './styles';

/**
 * Every `vaadin.*` meter in the registry, with a trend.
 *
 * This is the panel the kit shipped before there was a profiler, carried over
 * unchanged in behaviour: the same table, the same cumulative figures, the
 * same twenty-sample sparkline. It answers a different question from the
 * interactions tab -- what the whole application has done, for everyone,
 * since it started -- and people read it today, so it keeps its place.
 */

const HISTORY_MAX = 20;

/**
 * Recent trend values per meter, kept at module scope so closing the panel
 * does not throw away the history the sparkline is drawn from.
 */
const history: Record<string, number[]> = {};

/**
 * Appends this poll's value to each meter's series, and forgets the meters
 * that were not reported, so the map cannot grow without bound in a
 * long-running dev session.
 */
export function recordHistory(meters: Meter[] | undefined): void {
  const live: Record<string, boolean> = {};
  (meters ?? []).forEach((meter) => {
    const key = meterKey(meter);
    live[key] = true;
    const value = trendValue(meter);
    if (typeof value !== 'number' || !isFinite(value)) {
      return;
    }
    const series = history[key] ?? (history[key] = []);
    series.push(value);
    if (series.length > HISTORY_MAX) {
      series.shift();
    }
  });
  Object.keys(history).forEach((key) => {
    if (!live[key]) {
      delete history[key];
    }
  });
}

export class OkMetersTable extends LitElement {
  static properties = {
    meters: { attribute: false },
    timestamp: { type: Number }
  };

  declare meters: Meter[];
  declare timestamp: number | null;

  constructor() {
    super();
    this.meters = [];
    this.timestamp = null;
  }

  static styles = [tokens, css`
    :host {
      display: block;
      overflow-y: auto;
      padding: calc(var(--ok-space) * 3);
    }

    .when {
      color: var(--ok-muted);
      margin-bottom: calc(var(--ok-space) * 2);
    }

    table {
      border-collapse: collapse;
      inline-size: 100%;
    }

    th {
      border-bottom: 1px solid var(--ok-line);
      color: var(--ok-muted);
      font-weight: 400;
      padding: var(--ok-space) calc(var(--ok-space) * 2);
      text-align: start;
    }

    td {
      border-bottom: 1px solid var(--ok-line);
      padding: calc(var(--ok-space) * 1.5) calc(var(--ok-space) * 2);
      vertical-align: top;
    }

    td.value {
      white-space: nowrap;
    }

    td.trend {
      inline-size: 84px;
      vertical-align: middle;
    }

    .tags {
      color: var(--ok-muted);
      font-size: var(--ok-font-size-xs);
      overflow-wrap: anywhere;
    }

    .empty {
      color: var(--ok-muted);
    }

    svg {
      color: var(--s-server);
      display: block;
    }
  `];

  render(): unknown {
    if (this.meters.length === 0) {
      return html`<div class="empty">
        No Vaadin meters yet. Interact with the application to generate
        metrics.
      </div>`;
    }
    const meters = [...this.meters]
      .sort((a, b) => a.name.localeCompare(b.name));
    return html`
      <div class="when">
        ${meters.length} meter(s)${this.timestamp
          ? html` · updated ${new Date(this.timestamp).toLocaleTimeString()}`
          : nothing}
      </div>
      <table>
        <thead>
          <tr>
            <th>Meter</th>
            <th>Value</th>
            <th>Trend</th>
          </tr>
        </thead>
        <tbody>
          ${meters.map((meter) => this.row(meter))}
        </tbody>
      </table>
    `;
  }

  private row(meter: Meter): TemplateResult {
    const tags = formatTags(meter.tags);
    return html`
      <tr>
        <td>
          ${meter.name}
          ${tags ? html`<div class="tags">${tags}</div>` : nothing}
        </td>
        <td class="value num">${formatMeterValue(meter)}</td>
        <td class="trend">${this.sparkline(history[meterKey(meter)])}</td>
      </tr>
    `;
  }

  /** A meter's recent values, drawn where the number gives no shape. */
  private sparkline(values: number[] | undefined): unknown {
    if (!values || values.length < 2) {
      return nothing;
    }
    const w = 84;
    const h = 18;
    const pad = 2;
    const min = Math.min(...values);
    const max = Math.max(...values);
    const range = max - min || 1;
    const points = values.map((value, index) => {
      const x = pad + (index / (values.length - 1)) * (w - 2 * pad);
      const y = h - pad - ((value - min) / range) * (h - 2 * pad);
      return `${x.toFixed(1)},${y.toFixed(1)}`;
    }).join(' ');
    // The <svg> root is in the template, so the HTML parser switches into
    // the SVG namespace for it and lit's plain html tag is enough.
    return html`
      <svg width=${w} height=${h} viewBox="0 0 ${w} ${h}">
        <polyline
          points=${points}
          fill="none"
          stroke="currentColor"
          stroke-width="1.25"
          stroke-linejoin="round"
          stroke-linecap="round" />
      </svg>
    `;
  }
}

customElements.define('ok-meters-table', OkMetersTable);
